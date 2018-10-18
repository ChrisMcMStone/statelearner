/*
 *  Copyright (c) 2016 Joeri de Ruiter
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package nl.cypherpunk.statelearner;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;

import javax.annotation.ParametersAreNonnullByDefault;

import net.automatalib.words.Word;
import net.automatalib.words.WordBuilder;
import nl.cypherpunk.learningpurpose.LearningPurpose;
import nl.cypherpunk.learningpurpose.LearningPurposeWiFi;
import de.learnlib.api.MembershipOracle;
import de.learnlib.api.MembershipOracle.MealyMembershipOracle;
import de.learnlib.api.Query;
import de.learnlib.api.SUL;
import de.learnlib.logging.LearnLogger;

// Based on SULOracle from LearnLib by Falk Howar and Malte Isberner
@ParametersAreNonnullByDefault
public class LogOracle<I, D> implements MealyMembershipOracle<I, D> {
	public static class MealyLogOracle<I, O> extends LogOracle<I, O> {
		public MealyLogOracle(SUL<I, O> sul, LearnLogger logger, LearningConfig config) {
			super(sul, logger, config);
		}

	}

	LearnLogger logger;
	SUL<I, D> sul;
	Connection dbConn;
	ArrayList<ArrayList<String[]>> expected_flows;
	boolean use_cache = false;
	boolean time_learn = false;
	LearningPurposeWiFi lp;
	public static String DISABLE_OUTPUT = "-";
	boolean need_optimise = false;

	public LogOracle(SUL<I, D> sul, LearnLogger logger, LearningConfig config) {
		this.sul = sul;
		this.logger = logger;
		if (config.use_cache || config.time_learn)
			this.dbConn = config.getDbConn();
		if (config.use_cache) {
			this.expected_flows = config.expected_flows;
			this.use_cache = true;
		}
		if (config.time_learn) {
			this.time_learn = true;
			this.lp = new LearningPurposeWiFi(config);
		}
	}

	@Override
	public Word<D> answerQuery(Word<I> prefix, Word<I> suffix) {
		if (use_cache) {
			return answerQuery(prefix, suffix, true);
		} else {
			return answerQuery(prefix, suffix, false);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public Word<D> answerQuery(Word<I> query) {
		return answerQuery((Word<I>) Word.epsilon(), query);
	}

	@Override
	public MembershipOracle<I, Word<D>> asOracle() {
		return this;
	}

	@Override
	public void processQueries(Collection<? extends Query<I, Word<D>>> queries) {
		for (Query<I, Word<D>> q : queries) {
			Word<D> output = answerQuery(q.getPrefix(), q.getSuffix());
			q.answer(output);
		}
	}

	public Word<D> answerQuery(Word<I> prefix, Word<I> suffix, boolean cacheLookup) {
		
		need_optimise = false;

		// Check if query has been already cached
		Word<I> query = prefix.concat(suffix);
		if (cacheLookup) {
			Word<D> dbresponse = Utils.cacheLookupQuery(query.toString(), suffix.size(), dbConn);
			if (dbresponse != null) {
				logger.logQuery(
						"DB CACHE [" + prefix.toString() + " | " + suffix.toString() + " /  " + dbresponse + "]");
				return dbresponse;
			}
		}

		if (time_learn && cacheLookup) {
			Word<String> resp = Utils.responseIfDisabled(query.toString(), dbConn);
			if (resp != null) {
				logger.logQuery("DISABLED [" + prefix.toString() + " | " + suffix.toString() + " /  " + resp + "]");
				// Utils.cacheStringQueryResponse(query.toString(), resp, dbConn, true);
				if(suffix.length() == query.length()) {
					return (Word<D>)resp;
				} else {
					return (Word<D>)resp.suffix(suffix.length());
				}
			}
		}

		if (time_learn)
			this.lp.reset();
		this.sul.pre();

		try {
			// Prefix: Execute symbols, only log output
			WordBuilder<D> wbPrefix = new WordBuilder<>(prefix.length());
			WordBuilder<D> wbPrefixNoTime = new WordBuilder<>(prefix.length());
			for (I sym : prefix) {
				D res;
				if (time_learn) {
					if (lp.run((String) sym)) {
						res = this.sul.step(sym);
						lp.run((String) res);
					} else {
						res = (D) DISABLE_OUTPUT;
					}
				} else {
					res = this.sul.step(sym);
				}
				wbPrefix.add(res);
				wbPrefixNoTime.add((D) Utils.stripTimestamp((String) res));
			}

			// Suffix: Execute symbols, outputs constitute output word
			WordBuilder<D> wbSuffix = new WordBuilder<>(suffix.length());
			WordBuilder<D> wbSuffixNoTime = new WordBuilder<>(prefix.length());
			for (I sym : suffix) {
				D res;
				if (time_learn) {
					if (lp.run((String) sym)) {
						res = this.sul.step(sym);
						lp.run((String) res);
					} else {
						res = (D) DISABLE_OUTPUT;
					}
				} else {
					res = this.sul.step(sym);
				}
				wbSuffix.add(res);
				wbSuffixNoTime.add((D) Utils.stripTimestamp((String) res));
			}

			logger.logQuery("[" + prefix.toString() + " | " + suffix.toString() + " / " + wbPrefix.toWord().toString()
					+ " | " + wbSuffix.toWord().toString() + "]");

			Word<D> response = wbPrefix.toWord().concat(wbSuffix.toWord());
			Word<D> responseNoTime = wbPrefixNoTime.toWord().concat(wbSuffixNoTime.toWord());

			// Check expected flows are compatible excluding timestamps
			for (ArrayList<String[]> flow : expected_flows) {
				boolean verify = true;
				// Check whether query prefix matches flow
				for (int i = 0; i < flow.size(); i++) {
					String[] qr = flow.get(i);
					if (!qr[0].equals(query.getSymbol(i).toString())) {
						verify = false;
						break;
					}
				}
				if (verify) {
					// If it does match, check outputs are consistent
					for (int i = 0; i < flow.size(); i++) {
						String[] qr = flow.get(i);
						if (!qr[1].equals(responseNoTime.getSymbol(i).toString())) {
							// retry
							logger.log(Level.INFO, "Expected Flow Inconsistency, retrying.");
							return answerQuery(prefix, suffix, cacheLookup);
						}
					}
				}
			}

			if (use_cache)
				Utils.cacheQueryResponse(query, response, dbConn);

			need_optimise = true;
			return wbSuffix.toWord();
		} finally {
			sul.post();
		}
	}

	/*
	 * Once query/response accepted by model, carry out optimizations
	 */
	public void lpPostProcess() {
		if(need_optimise) lp.optimise();
	}

}
