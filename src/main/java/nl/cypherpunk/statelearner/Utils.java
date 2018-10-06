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

import net.automatalib.words.Word;

public class Utils {
	private static String CHARS = "0123456789ABCDEF";

	public static String bytesToHex(byte[] bytes) {
		StringBuffer hex = new StringBuffer();

		for (int i = 0; i < bytes.length; i++) {
			int n1 = (bytes[i] >> 4) & 0x0F;
			hex.append(CHARS.charAt(n1));
			int n2 = bytes[i] & 0x0F;
			hex.append(CHARS.charAt(n2));
		}

		return hex.toString();
	}

	public static byte[] hexToBytes(String hex) {
		// TODO Check if string contains only hex characters
		if (hex.length() % 2 != 0)
			hex = "0" + hex;

		byte[] bytes = new byte[hex.length() / 2];

		for (int i = 0; i < hex.length(); i = i + 2) {
			bytes[i / 2] = Integer.decode("0x" + hex.substring(i, i + 2)).byteValue();
		}

		return bytes;
	}

	/*
	 * Cache the query and response (including constituent sub queries and
	 * responses) and update observation counters.
	 */
	public static void cacheQueryResponse(Word query, Word response, Connection dbConn) {
		for (int i = 1; i < response.length() + 1; i++) {
			Word subPref = query.prefix(i);
			Word subResp = response.prefix(i);
			Statement stmt = null;
			try {
				stmt = dbConn.createStatement();
				stmt.executeUpdate(
						"INSERT INTO CACHE (PREFIX_ID, RESPONSE) " + "VALUES ('" + subPref + "', '" + subResp + "');");
				stmt.executeUpdate("UPDATE CACHE SET COUNT = COUNT + 1 WHERE PREFIX_ID = '" + subPref.toString()
						+ "' AND RESPONSE = '" + subResp + "'");
				stmt.close();
			} catch (Exception e) {
				if (e.getMessage().contains("UNIQUE")) {
					try {
						stmt.executeUpdate("UPDATE CACHE SET COUNT = COUNT + 1 WHERE PREFIX_ID = '" + subPref.toString()
								+ "' AND RESPONSE = '" + subResp + "'");
						stmt.close();
					} catch (Exception e2) {
						System.err.println(e2.getClass().getName() + ": " + e2.getMessage());
					}
				} else {
					System.err.println(e.getClass().getName() + ": " + e.getMessage());
				}
			}
		}
	}

	/**
	 * 
	 * Check DB Cache to see if query already been posed to SUL. If so, return the
	 * most commonly observed response.
	 * 
	 * @param query
	 *            String representation of query, space delimeted words
	 * @param suffixSize
	 *            Size of suffix of response, 0 for entire thing
	 * @param dbConn
	 * @return Most common response to query
	 */
	public static Word cacheLookupQuery(String query, int suffixSize, Connection dbConn) {
		Statement stmt = null;
		ResultSet rs = null;
		try {
			stmt = dbConn.createStatement();
			String qry1 = "SELECT * FROM CACHE WHERE PREFIX_ID = '" + query + "' ORDER BY COUNT DESC, ID ASC;";
			// System.out.println(qry1);
			rs = stmt.executeQuery(qry1);
			if (rs.next()) {
				String x = rs.getString("RESPONSE");
				String[] splitStr = x.trim().split("\\s+");
				try {
					if (suffixSize == 0) {
						Word response = Word.fromArray(splitStr, 0, splitStr.length);
						return response;
					} else {
						Word response = Word.fromArray(splitStr, splitStr.length - suffixSize, suffixSize);
						return response;
					}
				} catch (Exception e) {
					System.err.println(e.getClass().getName() + ": " + e.getMessage());
				}
			}
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
		} finally {
			try {
				rs.close();
				stmt.close();
			} catch (Exception e) {
				System.err.println(e.getClass().getName() + ": " + e.getMessage());
			}
		}
		return null;
	}

	public static int correctDBcache(String inconsistentPrefix, String inconsistentResponse, Connection dbConn) {
		// TODO Auto-generated method stub
		Statement stmt = null;
		if (inconsistentPrefix.substring(0, 1).equals("ε"))
			inconsistentPrefix = inconsistentPrefix.substring(2);
		try {
			stmt = dbConn.createStatement();
			String qry = "DELETE FROM CACHE WHERE PREFIX_ID LIKE '" + inconsistentPrefix + "%' AND RESPONSE NOT LIKE '"+inconsistentResponse+"%'";
			return stmt.executeUpdate(qry);
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
		} finally {
			try {
				stmt.close();
			} catch (Exception e) {
			}
		}
		return -1;
	}

}
