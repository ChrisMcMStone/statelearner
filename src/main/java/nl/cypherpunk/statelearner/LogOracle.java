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
import java.sql.Statement;
import java.util.Collection;

import javax.annotation.ParametersAreNonnullByDefault;

import net.automatalib.words.Word;
import net.automatalib.words.WordBuilder;
import de.learnlib.api.MembershipOracle;
import de.learnlib.api.MembershipOracle.MealyMembershipOracle;
import de.learnlib.api.Query;
import de.learnlib.api.SUL;
import de.learnlib.logging.LearnLogger;

// Based on SULOracle from LearnLib by Falk Howar and Malte Isberner
@ParametersAreNonnullByDefault
public class LogOracle<I, D> implements MealyMembershipOracle<I,D> {
	public static class MealyLogOracle<I,O> extends LogOracle<I,O> {
		public MealyLogOracle(SUL<I, O> sul, LearnLogger logger, Connection dbConn) {
			super(sul, logger, dbConn);
		}
	}
	
	LearnLogger logger;
	SUL<I, D> sul;
	Connection dbConn;
   
    public LogOracle(SUL<I,D> sul, LearnLogger logger, Connection dbConn) {        
        this.sul = sul;
        this.logger = logger;
        this.dbConn = dbConn;
    }
    
    @Override
	public Word<D> answerQuery(Word<I> prefix, Word<I> suffix) {
		this.sul.pre();
		
		//TODO check database cache for most common response first. 
		
		try {
			// Prefix: Execute symbols, only log output
			WordBuilder<D> wbPrefix = new WordBuilder<>(prefix.length());
			for(I sym : prefix) {
				wbPrefix.add(this.sul.step(sym));
			}
			
			// Suffix: Execute symbols, outputs constitute output word
			WordBuilder<D> wbSuffix = new WordBuilder<>(suffix.length());
			for(I sym : suffix) {
				wbSuffix.add(this.sul.step(sym));
			}
			
	    	logger.logQuery("[" + prefix.toString() + " | " + suffix.toString() +  " / " + wbPrefix.toWord().toString() + " | " + wbSuffix.toWord().toString() + "]");
	    	
	    	Word<I> query = prefix.concat(suffix);
	    	Word<D> response = wbPrefix.toWord().concat(wbSuffix.toWord());
	    	
	    	cacheQueryResponse(query, response);
	    	updateQueryCounters(query, response);
	    	
			return wbSuffix.toWord();
		}
		finally {
			sul.post();
		}
    }    
    
	@Override
    @SuppressWarnings("unchecked")
	public Word<D> answerQuery(Word<I> query) {
		return answerQuery((Word<I>)Word.epsilon(), query);
    }
    
    @Override
    public MembershipOracle<I, Word<D>> asOracle() {
    	return this;
    }

	@Override
	public void processQueries(Collection<? extends Query<I, Word<D>>> queries) {
		for (Query<I,Word<D>> q : queries) {
			Word<D> output = answerQuery(q.getPrefix(), q.getSuffix());
			q.answer(output);
		}
	}
	
	private void updateQueryCounters(Word<I> query, Word<D> response) {
		for(int i=1;i<response.length()+1;i++) {
			Word<I> sub = query.prefix(i);
			Statement stmt = null;
			try {
				stmt = dbConn.createStatement();
				stmt.executeUpdate("UPDATE CACHE SET COUNT = COUNT + 1 WHERE PREFIX_ID = \""+ sub.toString() + "\"");
				stmt.close();
			} catch (Exception e) {
				System.out.println("No such query subword");
			}
		}
	}
	
	private boolean cacheQueryResponse(Word<I> query, Word<D> response) {
		String q = query.toString();
		String r = response.toString();
		Statement stmt = null;
		try {
			stmt = dbConn.createStatement();
			if (r.substring(0, 2).equals("ε "))
				r = r.substring(2);
			stmt.executeUpdate("INSERT INTO CACHE (PREFIX_ID, RESPONSE) " + "VALUES ('" + q + "', '" + r + ");");
			stmt.close();
			return true;
		} catch (Exception e) {
			return false;
		}

	}
}
