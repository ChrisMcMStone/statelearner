package nl.cypherpunk.learningpurpose;

import java.sql.Connection;
import java.util.ArrayList;

import net.automatalib.words.Word;
import net.automatalib.words.WordBuilder;
import net.automatalib.words.impl.SimpleAlphabet;
import nl.cypherpunk.statelearner.LearningConfig;
import nl.cypherpunk.statelearner.LogOracle;
import nl.cypherpunk.statelearner.Utils;

public class LearningPurposeWiFi {

	private Connection dbConn;
	private int state = 0;
	private String last_message = "";
	private ArrayList<String> resetOutputs;
	private ArrayList<String> postRetransInputs;
	private int queryIndex = 0;
	private WordBuilder<String> query;
	private WordBuilder<String> response;
	private SimpleAlphabet<String> alphabet;

	public LearningPurposeWiFi(LearningConfig config) {
		this.dbConn = config.getDbConn();

		// Reset/Disable outputs
		this.resetOutputs = config.getDisable_outputs();
		// Inputs enabled after a retransmission observed
		this.postRetransInputs = config.getRetrans_enabled();
		this.query = new WordBuilder<>();
		this.response = new WordBuilder<>();
		this.alphabet = config.getAlphabet();
	}

	public boolean run(String sym) {
		this.queryIndex++;
		if (queryIndex >= 2 && sym.contains("ASSOC")) {
			state = -2;
			return false;
		}
		switch (state) {
		// Non-optimised disable state
		case -2:
			return false;
		// Optimised disable state
		case -1:
			return false;
		case 0:
			state0(sym);
			return true;
		case 1:
			state1(sym);
			return true;
		case 2:
			state2(sym);
			return true;
		case 3:
			state3(sym);
		case 4:
			state4(sym);
		default:
			return true;
		}
	}

	public void reset() {
		this.state = 0;
		this.queryIndex = 0;
		this.response.clear();
		this.query.clear();
		this.last_message = "";
	}

	/*
	 * All inputs allowed at start
	 */
	private void state0(String input) {
		this.query.append(input);
		this.state = 1;
		// Enforce queries start with association, and disallow reassociations
	}

	private void state1(String output) {
		this.response.append(output);
		if (Utils.stripTimestamp(output).equals(this.last_message)) {
			this.state = 2;
		} else if (resetOutputs.contains(Utils.stripTimestamp(output))) {
			this.state = -1;
		} else if(output.contains("TIMEOUT")) {
			this.state = 3;
		}else {
			this.state = 0;
		}
		this.last_message = Utils.stripTimestamp(output);
	}

	private void state2(String input) {
		this.query.append(input);
		if (postRetransInputs.contains(input)) {
			this.state = 1;
		} else {
			this.state = -1;
		}
	}
	
	private void state3(String input) {
		this.query.append(input);
		if (input.contains("DATA")) {
			this.state = 4;
		} else {
			this.state = -1;
		}
	}
	
	private void state4(String output) {
			this.state = -1;
	}

	private void optimiseState2() {
		if (query.size() != response.size()) {
			// This shouldn't happen, but if it does we can safely return null
			return;
		}
		for (String s : this.alphabet) {
			if (!this.postRetransInputs.contains(s)) {
				String q = this.query.toWord().toString() + " " + s;
				String r = this.response.toWord().toString() + " " + LogOracle.DISABLE_OUTPUT;
				Utils.cacheStringQueryResponse(q, r, dbConn, true);
			}
		}
	}

	private void optimiseDisableState() {
		if (query.size() != response.size()) {
			// This shouldn't happen, but if it does we can safely return null
			return;
		}
		for (String s : this.alphabet) {
			String q = this.query.toWord().toString() + " " + s;
			String r = this.response.toWord().toString() + " " + LogOracle.DISABLE_OUTPUT;
			Utils.cacheStringQueryResponse(q, r, dbConn, true);
		}
	}

	public void optimise() {
		if (this.state == 2) {
			optimiseState2();
		} else if (this.state == -1) {
			optimiseDisableState();
		}
	}

}
