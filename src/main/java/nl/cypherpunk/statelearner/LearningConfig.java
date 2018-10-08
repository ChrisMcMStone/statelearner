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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Level;

import org.apache.commons.lang3.StringUtils;

import de.learnlib.logging.LearnLogger;

/**
 * Configuration class used for learning parameters
 * 
 * @author Joeri de Ruiter (joeri@cs.ru.nl)
 */
public class LearningConfig {
	static int TYPE_SMARTCARD = 1;
	static int TYPE_SOCKET = 2;
	static int TYPE_TLS = 3;

	protected Properties properties;

	int type = TYPE_SMARTCARD;

	String output_dir = "output";

	String learning_algorithm = "lstar";
	String eqtest = "randomwords";

	// Handles lossy connections
	boolean use_cache = false;
	ArrayList<ArrayList<String[]>> expected_flows;
	Connection dbConn;

	// Used for W-Method and Wp-method
	int max_depth = 10;

	// Used for Random words
	int min_length = 5;
	int max_length = 10;
	int nr_queries = 100;
	int seed = 1;

	public LearningConfig(String filename) throws IOException {
		properties = new Properties();

		InputStream input = new FileInputStream(filename);
		properties.load(input);

		loadProperties();
		if (use_cache) {
			try {
				setUpdDBConn();
			} catch (Exception e) {
				System.err.println("DB Connection failed");
				e.printStackTrace();
				System.exit(1);
			}
		}
	}

	public LearningConfig(LearningConfig config) {
		properties = config.getProperties();
		loadProperties();
	}

	public Properties getProperties() {
		return properties;
	}

	public void loadProperties() {
		if (properties.getProperty("output_dir") != null)
			output_dir = properties.getProperty("output_dir");

		if (properties.getProperty("type") != null) {
			if (properties.getProperty("type").equalsIgnoreCase("smartcard"))
				type = TYPE_SMARTCARD;
			else if (properties.getProperty("type").equalsIgnoreCase("socket"))
				type = TYPE_SOCKET;
			else if (properties.getProperty("type").equalsIgnoreCase("tls"))
				type = TYPE_TLS;
		}

		if (properties.getProperty("learning_algorithm").equalsIgnoreCase("lstar")
				|| properties.getProperty("learning_algorithm").equalsIgnoreCase("dhc")
				|| properties.getProperty("learning_algorithm").equalsIgnoreCase("kv")
				|| properties.getProperty("learning_algorithm").equalsIgnoreCase("ttt")
				|| properties.getProperty("learning_algorithm").equalsIgnoreCase("mp")
				|| properties.getProperty("learning_algorithm").equalsIgnoreCase("rs"))
			learning_algorithm = properties.getProperty("learning_algorithm").toLowerCase();

		if (properties.getProperty("eqtest") != null && (properties.getProperty("eqtest").equalsIgnoreCase("wmethod")
				|| properties.getProperty("eqtest").equalsIgnoreCase("modifiedwmethod")
				|| properties.getProperty("eqtest").equalsIgnoreCase("wpmethod")
				|| properties.getProperty("eqtest").equalsIgnoreCase("randomwords")))
			eqtest = properties.getProperty("eqtest").toLowerCase();

		if (properties.getProperty("max_depth") != null)
			max_depth = Integer.parseInt(properties.getProperty("max_depth"));

		if (properties.getProperty("min_length") != null)
			min_length = Integer.parseInt(properties.getProperty("min_length"));

		if (properties.getProperty("max_length") != null)
			max_length = Integer.parseInt(properties.getProperty("max_length"));

		if (properties.getProperty("nr_queries") != null)
			nr_queries = Integer.parseInt(properties.getProperty("nr_queries"));

		if (properties.getProperty("seed") != null)
			seed = Integer.parseInt(properties.getProperty("seed"));

		if (properties.getProperty("use_cache") != null)
			use_cache = true;

		if (properties.getProperty("expected_flows") != null)
			parseFlows(properties.getProperty("expected_flows"));
	}

	private void parseFlows(String p) {
		// TODO Auto-generated method stub
		expected_flows = new ArrayList<ArrayList<String[]>>();

		if (StringUtils.countMatches(p, '[') != StringUtils.countMatches(p, ']')) {
			System.err.println("Failed formatting of expected flows 1");
			System.exit(1);
		}
		int no_flows = StringUtils.countMatches(p, '[');

		// For each flow
		for (int i = 1; i < no_flows + 1; i++) {
			System.out.println(StringUtils.countMatches(p, "["));
			int start = StringUtils.ordinalIndexOf(p, "[", i);
			int end = StringUtils.ordinalIndexOf(p, "]", i);
			String flow = p.substring(start + 1, end);

			if (StringUtils.countMatches(flow, '{') != StringUtils.countMatches(flow, '}')) {
				System.err.println("Failed formatting of expected flows 2");
				System.exit(1);
			}
			int queries = StringUtils.countMatches(flow, '{');

			ArrayList<String[]> flow_elem = new ArrayList<>();
			// For each query/response
			for (int j = 1; j < queries + 1; j++) {
				int start_query = StringUtils.ordinalIndexOf(flow, "{", j);
				int end_query = StringUtils.ordinalIndexOf(flow, "}", j);
				String query_response = flow.substring(start_query + 1, end_query);
				int sep_index = query_response.indexOf(':');
				String query = query_response.substring(0, sep_index);
				String response = query_response.substring(sep_index + 1);
				String[] elem = { query, response };
				flow_elem.add(elem);
			}
			expected_flows.add(flow_elem);
		}
	}

	private void setUpdDBConn() throws Exception {
		LearnLogger log = LearnLogger.getLogger(Learner.class.getSimpleName());
		Class.forName("org.sqlite.JDBC");
		this.dbConn = DriverManager.getConnection("jdbc:sqlite:cache.db");
		Statement stmt = null;
		stmt = dbConn.createStatement();
		String sql = "CREATE TABLE IF NOT EXISTS CACHE" + "(ID INTEGER PRIMARY KEY, PREFIX_ID TEXT NOT NULL,"
				+ "RESPONSE	TEXT	NOT NULL,  COUNT INT DEFAULT 0, CONSTRAINT xyz UNIQUE (PREFIX_ID, RESPONSE))";
		stmt.executeUpdate(sql);
		stmt.close();
		log.log(Level.INFO, "Successfully set up caching database");
	}

}
