package nl.cyperpunk.learningpurpose;

import java.sql.Connection;
import java.util.ArrayList;

import nl.cypherpunk.statelearner.LearningConfig;
import nl.cypherpunk.statelearner.Utils;

public class LearningPurpose {

	private Connection dbConn;
	private int state = 0;
	private static String DISABLE_SYM = "-";
	private String last_message = "";
	private ArrayList<String> resetOutputs;
	private ArrayList<String> postRetransInputs;

	public LearningPurpose(LearningConfig config) {
		this.dbConn = config.getDbConn();
		
		//Reset/Disable outputs
		this.resetOutputs = config.getDisable_outputs();
		//Inputs enabled after a retransmission observed
		this.postRetransInputs = config.getRetrans_enabled();
	}

	public boolean run(String sym) {
		switch (state) {
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
		default:
			return true;
		}
	}

	public void reset() {
		this.state = 0;
	}

	/*
	 * All inputs allowed at start
	 */
	private void state0(String input) {
		this.state=1;
	}

	private void state1(String output) {
		if(Utils.stripTimestamp(output).equals(this.last_message)) {
			this.state=2;
		} else if(resetOutputs.contains(Utils.stripTimestamp(output))) {
			this.state = -1;
		} else {
			this.state = 0;
		}
	}

	private void state2(String input) {
		if(postRetransInputs.contains(input)) {
			this.state = 1;
		} else {
			this.state = -1;
		}
	}

}
