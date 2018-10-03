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
			Mapping<? super O, ? extends O> errorSyms, MembershipOracle<I, Word<O>> delegate, Connection dbConn) {
		IncrementalMealyBuilder<I, O> incrementalBuilder = new IncrementalMealyDAGBuilder<>(inputAlphabet);
		return new MealyCacheOracle<>(incrementalBuilder, errorSyms, delegate, dbConn);
	}

	private final MembershipOracle<I, Word<O>> delegate;
	private final IncrementalMealyBuilder<I, O> incMealy;
	private final Lock incMealyLock;
	private final Comparator<? super Query<I, ?>> queryCmp;
	private final Mapping<? super O, ? extends O> errorSyms;
	private boolean retryQuery = false;
	private static final int RETRY_THRESHOLD = 3;
	private Connection dbConn;
	private LearnLogger log;

	public MealyCacheOracle(IncrementalMealyBuilder<I, O> incrementalBuilder, Mapping<? super O, ? extends O> errorSyms,
			MembershipOracle<I, Word<O>> delegate, Connection dbConn) {
		this(incrementalBuilder, new ReentrantLock(), errorSyms, delegate, dbConn);
	}

	public MealyCacheOracle(IncrementalMealyBuilder<I, O> incrementalBuilder, Lock lock,
			Mapping<? super O, ? extends O> errorSyms, MembershipOracle<I, Word<O>> delegate, Connection dbConn) {
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
		Word<I> word = master.getSuffix();
		Word<O> answer = master.getAnswer();
		Word<I> query = master.getPrefix().concat(master.getSuffix());
		List<DefaultQuery<I, Word<O>>> queries = null;

		if (errorSyms == null) {
			String inconsistentPrefix = null;
			try {
				incMealy.insert(word, answer);
			} catch (ConflictException e) {
				inconsistentPrefix = e.getMessage();
				log.log(Level.INFO, "Detected non-determinsm prefix: " + inconsistentPrefix);
				
				//Update query counters or add new entry in cache
				//Determine what is most common response to 'query'
				//

				return;
			}
		}

		// Never executed because errorsyms always null.
		int answLen = answer.length();
		int i = 0;
		while (i < answLen) {
			O sym = answer.getSymbol(i++);
			if (errorSyms.get(sym) != null)
				break;
		}

		if (i == answLen) {
			incMealy.insert(word, answer);
		} else {
			incMealy.insert(word.prefix(i), answer.prefix(i));
		}
	}

//	private void postProcess2(MasterQuery<I, O> master) {
//		Word<I> word = master.getSuffix();
//		Word<O> answer = master.getAnswer();
//		Word<I> query = master.getPrefix().concat(master.getSuffix());
//		List<DefaultQuery<I, Word<O>>> queries = null;
//
//		// errorSyms is always null in our set up. Therfore, at this conditional,
//		// function either returns or throws exception
//		if (errorSyms == null) {
//			String inconsistentPrefix = null;
//			// incMealy.insert(word, answer);
//			try {
//				incMealy.insert(word, answer);
//			} catch (ConflictException e) {
//				// Need to subtract from
//				inconsistentPrefix = e.getMessage();
//				log.log(Level.INFO, "Detected non-determinsm prefix: " + inconsistentPrefix);
//
//				this.retryQuery = true;
//				master = new MasterQuery<I, O>(query);
//				correctDBcache(query.toString());
//				updateQueryCounters(query, false);
//				int count = getQueryAttemptNo(inconsistentPrefix);
//				if (count > 20) {
//					for (int i = 0; i < count; i++) {
//						try {
//							Thread.sleep(i * 1000);
//							System.out.println("Retrying big count " + i);
//							log.log(Level.INFO, "Retrying big count " + i);
//							delegate.processQuery(master);
//							postProcess(master);
//							for (Query<I, Word<O>> q : master.getSlaves()) {
//								q = new DefaultQuery<>(query);
//								q.answer(master.getAnswer());
//							}
//							// queries.get(0).getOutput();
//							// If no exception thrown then consistent, break out of loop
//							this.retryQuery = false;
//							return;
//						} catch (ConflictException b) {
//							inconsistentPrefix = b.getMessage();
//							correctDBcache(master.getPrefix().toString() + " " + master.getSuffix().toString());
//							System.out.println("Retrying");
//							continue;
//						} catch (InterruptedException c) {
//							// TODO
//						}
//					}
//				} else {
//					for (int i = 0; i < 3; i++) {
//						try {
//							Thread.sleep(1000);
//							System.out.println("Retrying");
//							System.out.println("Retrying small count " + i);
//							log.log(Level.INFO, "Retrying small count " + i);
//							delegate.processQuery(master);
//							postProcess(master);
//							for (Query<I, Word<O>> q : master.getSlaves()) {
//								q = new DefaultQuery<>(query);
//								q.answer(master.getAnswer());
//							}
//							// queries.get(0).getOutput();
//							// If no exception thrown then consistent, break out of loop
//							this.retryQuery = false;
//							return;
//						} catch (ConflictException b) {
//							inconsistentPrefix = b.getMessage();
//							correctDBcache(master.getPrefix().toString() + " " + master.getSuffix().toString());
//							continue;
//						} catch (InterruptedException c) {
//							// TODO
//						}
//					}
//					// If Retry_Threshold exceeded, delete from db cache
//					if (inconsistentPrefix.equals("ASSOC(RSNE=cc) DELAY"))
//						throw new ConflictException("Assoc delay inconsistent");
//					correctDBcache(inconsistentPrefix);
//					log.log(Level.INFO, "Deleting all cached queries with inconsisent prefix: " + inconsistentPrefix);
//					throw new ConflictException("Failed initial consistency correction, deleted all prefixes");
//				}
//			}
//			return;
//		}
//	}
//
//	int answLen = answer.length();
//	int i = 0;while(i<answLen)
//	{
//		O sym = answer.getSymbol(i++);
//		if (errorSyms.get(sym) != null)
//			break;
//	}
//
//	if(i==answLen)
//	{
//		incMealy.insert(word, answer);
//	}else
//	{
//		incMealy.insert(word.prefix(i), answer.prefix(i));
//	}
//	}

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

	private int correctDBcache(String inconsistenPrefix) {
		// TODO Auto-generated method stub
		Statement stmt = null;
		if (inconsistenPrefix.substring(0, 1).equals("ε"))
			inconsistenPrefix = inconsistenPrefix.substring(2);
		try {
			stmt = dbConn.createStatement();
			log.log(Level.INFO, "Removing rows with prefix_id = " + inconsistenPrefix);
			System.out.println("Removing rows with prefix_id = " + inconsistenPrefix);
			return stmt.executeUpdate("DELETE FROM CACHE WHERE PREFIX_ID LIKE \"" + inconsistenPrefix + "%\"");
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
		} finally {
			try {
				stmt.close();
			} catch (SQLException e) {
			}
		}
		return -1;
	}

	private void updateQueryCounters(Word<I> query, boolean increment) {
		for (int i = 1; i < query.length() + 1; i++) {
			Word<I> sub = query.prefix(i);
			Statement stmt = null;
			try {
				stmt = dbConn.createStatement();
				String op = "-";
				if (increment)
					op = "+";
				stmt.executeUpdate(
						"UPDATE CACHE SET COUNT = COUNT " + op + " 1 WHERE PREFIX_ID = \"" + sub.toString() + "\"");
				stmt.close();
			} catch (Exception e) {
				System.out.println("No such query subword");
			}
		}
	}

	private int getQueryAttemptNo(String query) {
		Statement stmt = null;
		ResultSet rs = null;
		try {
			stmt = dbConn.createStatement();
			rs = stmt.executeQuery("SELECT COUNT FROM CACHE WHERE PREFIX_ID = \"" + query + "\"");
			stmt.close();
			if (rs.next()) {
				return rs.getInt("COUNT");
			}
		} catch (Exception e) {
			System.out.println("No such query subword");
			return 0;
		}
		return 0;
	}

}
