# StateLearner Extension

StateLearner is a tool that can learn state machines from implementations using a black-box approach. It makes use of LearnLib for the learning specific algorithms.

This is a fork of StateLearner with added support for handling lossy protocols (i.e. non-deterministism) + efficient learning of time based behavior (e.g. timeouts and retransmissions).

This is one part of the tool for learning state machines of the WiFi security handshake. The other required tool can be found [here](https://github.com/ChrisMcMStone/wifi-learner). 

The research paper published as a result of this work can be found here: [Extending Automated Protocol State Learning for the 802.11 4-Way Handshake](http://www.cs.bham.ac.uk/~tpc/Papers/WPAlearning.pdf),

StateLearner can be used for TLS implementations, smart cards and can be extended using its socket module. 

An overview of different security protocols where state machine learning has been applied can be found [here](http://www.cs.ru.nl/~joeri/StateMachineInference.html).

## Requirements

* graphviz

## Build

Build a self-contained jar file using the following command:

`mvn package shade:shade`

## Usage

`java -jar stateLearner-0.0.1-SNAPSHOT.jar <configuration file>`

Example configurations can be found in the 'examples' directory. To run the OpenSSL example:

```
cd examples/openssl
java -jar ../../target/stateLearner-0.0.1-SNAPSHOT.jar server.properties
```

## Publications

StateLearner (or one of its predecessors) has been used in the following publications:
* [Extending Automated Protocol State Learning for the 802.11 4-Way Handshake](http://www.cs.bham.ac.uk/~tpc/Papers/WPAlearning.pdf), Chris McMahon Stone, Tom Chothia, Joeri de Ruiter
* [Automated Reverse Engineering using Lego](https://www.usenix.org/conference/woot14/workshop-program/presentation/chalupar), Georg Chalupar, Stefan Peherstorfer, Erik Poll and Joeri de Ruiter
* [Protocol state fuzzing of TLS implementations](https://www.usenix.org/conference/usenixsecurity15/technical-sessions/presentation/de-ruiter), Joeri de Ruiter and Erik Poll
* [A Tale of the OpenSSL State Machine: a Large-scale Black-box Analysis](http://www.cs.ru.nl/~joeri/papers/nordsec16.pdf), Joeri de Ruiter
