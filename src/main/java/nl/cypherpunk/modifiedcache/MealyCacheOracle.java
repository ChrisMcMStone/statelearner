package nl.cypherpunk.modifiedcache;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
/* Copyright (C) 2013 TU Dortmund
 * This file is part of LearnLib, http://www.learnlib.de/.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import javax.naming.ConfigurationException;
import javax.swing.plaf.synth.SynthSeparatorUI;

import net.automatalib.commons.util.array.RichArray;
import net.automatalib.commons.util.comparison.CmpUtil;
import net.automatalib.commons.util.mappings.Mapping;
import net.automatalib.incremental.ConflictException;
import net.automatalib.incremental.mealy.IncrementalMealyBuilder;
import net.automatalib.incremental.mealy.tree.IncrementalMealyTreeBuilder;
import net.automatalib.words.Alphabet;
import net.automatalib.words.Word;
import net.automatalib.words.WordBuilder;
import nl.cypherpunk.modifiedcache.dag.*;
import de.learnlib.api.MembershipOracle;
import de.learnlib.api.Query;
import de.learnlib.cache.LearningCacheOracle.MealyLearningCacheOracle;
import de.learnlib.cache.mealy.MealyCacheConsistencyTest;
import de.learnlib.logging.LearnLogger;
import de.learnlib.oracles.DefaultQuery;

import nl.cypherpunk.statelearner.Utils;
import nl.cypherpunk.statelearner.LogOracle.MealyLogOracle;

/**
 * Mealy cache. This cache is implemented as a membership oracle: upon
 * construction, it is provided with a delegate oracle. Queries that can be
 * answered from the cache are answered directly, others are forwarded to the
 * delegate oracle. When the delegate oracle has finished processing these
 * remaining queries, the results are incorporated into the cache.
 * 
 * This oracle additionally enables the user to define a Mealy-style
 * prefix-closure filter: a {@link Mapping} from output symbols to output
 * symbols may be provided, with the following semantics: If in an output word a
 * symbol for which the given mapping has a non-null value is encountered, all
 * symbols <i>after</i> this symbol are replaced by the respective value. The
 * rationale behind this is that the concrete error message (key in the mapping)
 * is still reflected in the learned model, it is forced to result in a sink
 * state with only a single repeating output symbol (value in the mapping).
 * 
 * @author Malte Isberner
 *
 * @param <I>
 *            input symbol class
 * @param <O>
 *            output symbol class
 */
public class MealyCacheOracle<I, O> implements MealyLearningCacheOracle<I, O> {

	private static final class ReverseLexCmp<I> implements Comparator<Query<I, ?>> {
		private final Alphabet<I> alphabet;

		public ReverseLexCmp(Alphabet<I> alphabet) {
			this.alphabet = alphabet;
		}

		@Override
		public int compare(Query<I, ?> o1, Query<I, ?> o2) {
			return -CmpUtil.lexCompare(o1.getInput(), o2.getInput(), alphabet);
		}
	}

	public static <I, O> MealyCacheOracle<I, O> createDAGCacheOracle(Alphabet<I> inputAlphabet,
			Mapping<? super O, ? extends O> errorSyms, MealyLogOracle<I, O> delegate, Connection dbConn) {
		IncrementalMealyBuilder<I, O> incrementalBuilder = new IncrementalMealyDAGBuilder<>(inputAlphabet);
		return new MealyCacheOracle<>(incrementalBuilder, errorSyms, delegate, dbConn);
	}

	private final MealyLogOracle<I, O> delegate;
	private final IncrementalMealyBuilder<I, O> incMealy;
	private final Lock incMealyLock;
	private final Comparator<? super Query<I, ?>> queryCmp;
	private final Mapping<? super O, ? extends O> errorSyms;
	private Connection dbConn;
	private LearnLogger log;

	public MealyCacheOracle(IncrementalMealyBuilder<I, O> incrementalBuilder, Mapping<? super O, ? extends O> errorSyms,
			MealyLogOracle<I, O> delegate, Connection dbConn) {
		this(incrementalBuilder, new ReentrantLock(), errorSyms, delegate, dbConn);
	}

	public MealyCacheOracle(IncrementalMealyBuilder<I, O> incrementalBuilder, Lock lock,
			Mapping<? super O, ? extends O> errorSyms, MealyLogOracle<I, O> delegate, Connection dbConn) {
		this.incMealy = incrementalBuilder;
		this.incMealyLock = lock;
		this.queryCmp = new ReverseLexCmp<>(incrementalBuilder.getInputAlphabet());
		this.errorSyms = errorSyms;
		this.delegate = delegate;
		this.dbConn = dbConn;
		log = LearnLogger.getLogger("NONDETER");
	}

	public IncrementalMealyBuilder<I, O> getIncMealy() {
		return incMealy;
	}

	public int getCacheSize() {
		return incMealy.asGraph().size();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.learnlib.cache.LearningCache#createCacheConsistencyTest()
	 */
	@Override
	public MealyCacheConsistencyTest<I, O> createCacheConsistencyTest() {
		return new MealyCacheConsistencyTest<>(incMealy, incMealyLock);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.learnlib.api.MembershipOracle#processQueries(java.util.Collection)
	 */
	@Override
	public void processQueries(Collection<? extends Query<I, Word<O>>> queries) {
		if (queries.isEmpty()) {
			return;
		}

		RichArray<Query<I, Word<O>>> qrys = new RichArray<>(queries);
		qrys.parallelSort(queryCmp);

		List<MasterQuery<I, O>> masterQueries = new ArrayList<>();

		Iterator<Query<I, Word<O>>> it = qrys.iterator();
		Query<I, Word<O>> q = it.next();
		Word<I> ref = q.getInput();

		incMealyLock.lock();
		try {
			MasterQuery<I, O> master = createMasterQuery(ref);
			if (!master.isAnswered()) {
				masterQueries.add(master);
			}
			master.addSlave(q);

			while (it.hasNext()) {
				q = it.next();
				Word<I> curr = q.getInput();
				if (!curr.isPrefixOf(ref)) {
					master = createMasterQuery(curr);
					if (!master.isAnswered()) {
						masterQueries.add(master);
					}
				}
				master.addSlave(q);
				// Update ref to increase the effectiveness of the length check in
				// isPrefixOf
				ref = curr;
			}
		} finally {
			incMealyLock.unlock();
		}

		delegate.processQueries(masterQueries);

		incMealyLock.lock();
		try {
			for (MasterQuery<I, O> m : masterQueries) {
				postProcess(m);
			}
		} finally {
			incMealyLock.unlock();
		}
	}

	private void postProcess(MasterQuery<I, O> master) {
		Word<I> input_suffix = master.getSuffix();
		Word<O> answer = master.getAnswer();
		Word<I> input = master.getInput();

		if (errorSyms == null) {
			try {
				incMealy.insert(input_suffix, answer);
			} catch (ConflictException e) {
				// Assumes observation count already incremented.

				// Inconsistent query/response
				String qr = e.getMessage();
				// Inconsistent query
				String iq = qr.substring(0, qr.indexOf(" / "));
				// Inconsistent response
				String ir = qr.substring(qr.indexOf(" / ") + 3);

				// Get current most observed response
				Word<O> common_response = Utils.cacheLookupQuery(iq, 0, dbConn);

				// Check whether current model is wrong by testing equality between
				// common_response and ir (inconsistent response)
				if (common_response.toString().equals(ir)) {
					// Correct Cache and Restart learning
					Utils.correctDBcache(iq, ir, dbConn);
					log.log(Level.INFO, "Deleting all cached queries with inconsisent prefix: " + iq);
					throw new ConflictException("Failed initial consistency correction, deleted all prefixes");
				} else {
					// Retry
					MasterQuery<I, O> retry = createMasterQuery(input);
					//Ask query, dont use cache
					Word<O> output = delegate.answerQuery(Word.epsilon(),retry.getInput(), false);
					retry.answer(output);
					incMealyLock.lock();
					try {
						postProcess(retry);
					} finally {
						incMealyLock.unlock();
					}
				}
			}
		} else {

			// Never executed because errorsyms always null.
			int answLen = answer.length();
			int i = 0;
			while (i < answLen) {
				O sym = answer.getSymbol(i++);
				if (errorSyms.get(sym) != null)
					break;
			}

			if (i == answLen) {
				incMealy.insert(input_suffix, answer);
			} else {
				incMealy.insert(input_suffix.prefix(i), answer.prefix(i));
			}
		}
	}

	private MasterQuery<I, O> createMasterQuery(Word<I> word) {
		WordBuilder<O> wb = new WordBuilder<>();
		if (incMealy.lookup(word, wb)) {
			return new MasterQuery<>(word, wb.toWord());
		}

		if (errorSyms == null) {
			return new MasterQuery<>(word);
		}
		int wbSize = wb.size();
		O repSym;
		if (wbSize == 0 || (repSym = errorSyms.get(wb.getSymbol(wbSize - 1))) == null) {
			return new MasterQuery<>(word, errorSyms);
		}

		wb.repeatAppend(word.length() - wbSize, repSym);
		return new MasterQuery<>(word, wb.toWord());
	}

}
