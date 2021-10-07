Digital wallet Conclave
===================================

Introduction
------------

Digital Wallet implements the idea of an enclave that can simultaneously host Cards and Payments as 'Computations'. These computations are initiated by a Conclave client, have a name and a type, have a defined set of 'participants' (Card holder, Pay-server) and a 'quorum' that must be reached before a result (or results) can be obtained. Supported use cases are setup card and payment 

The idea is that an enclave of this type could be used to host simple one-off multi-party computations (eg Set up card involves an agreement from the pay server, pay requires a generating a token through pay server ). 

The comments in me/gendal/conclave/walletmanager/enclave/EventManagerEnclave.kt explain more.

The project also includes a command-line application for interacting with the enclave via a simple Spring Boot host server. There are three developer specific enhancements for ease of testing.

First, the client can be compiled to native (see below). Secondly, picocli has been integrated to make its use more intuitive. Thirdly, the identity persistence logic has been tweaked to make it possible to map between 'easy names' (eg Alice) and pubkeys. This is purely a convenience for demos.

Running
-------

The project is set up with a default of 'mock' mode so that it can be used immediately without any SGX hardware or need for a native-image build.  To run a simple example, open two terminals and change to the root directory for this project. The following instructions have been tested only on Mac, and probably only work on Mac:

1. In the first terminal, start the host: `./gradlew host:run`. The host will start listening on port 9999.
2. In the second terminal, build and package the client: `./gradlew client:shadowJar`
3. In the same terminal, change to the client subdirectory
4. Execute each of the scripts prefixed with 1 to 5 in order. 
    * The first script (`1.reset-client.sh`) will create a client identity ready for the following steps. This includes the creation of a set of `.conclave` files. These are read by any client upon startup in order to learn the 'easy' name of other clients running on the same machine (useful for demos)
    * `2.create-card.sh` TODO
    * `3.make-pay.sh` TODO
    * `4.get-statement.sh` TODO
    * Finally, `5.clean-up.sh` deletes the identity files
    
To run the host in any other mode, add `-PenclaveMode=[mock|simulation|debug|Release]` to the end of the gradlew command line in the usual manner.  If running on a Mac, you can use `container-gradle`, also in the usual way.  

Note that the host has been configured by default to listen on port 9999 (versus the Spring Boot default of 8080) in order to be compatible with the default in the container-gradle script.  If connecting to a non mock-mode enclave you will need to ensure the enclave constraint used by the client matches the enclave that is actually running.  See `WalletManagerClient.properties` to see how the client constraint is set.

To run the client in native mode, execute `./gradlew client:nativeImage` from the project's root folder. You can edit the `event-client.sh` script in the client folder to make the new native image the default for the demo scripts.  If the native build of the client fails, see the notes in `configure-native-image-build.sh`
    
PRs, comments, issues welcome

A note on security
------------------

This project has NOT been security reviewed.  

So, once you reach the point that your code is running, we advise you to think deeply about the various ways an adversary could break or subvert the application. Adversarial thinking is (and must be) at the heart of any enclave development project and you should start as early as possible in your journey. 

For example, this application has a 'lock' feature that is intended to ensure that all participants receive the same 'answer' for any calculation once it reaches quorum. However, think what happens if an adversarial host restarts the enclave after one party has received a result and then replays a stream of messages that omits one party's submission. Provided there were more submissions than needed to achieve quorum, the next party to request a result may get an answer that differs to the previous party's answer, subverting the 'lock' concept. Do we care about this? Perhaps it seems OK if different recipients get different results, provided the results are correct for the inputs from which they were derived. But now observe that a motivated adversarial host can do this as many times as they like. Does this attack give them the ability to deduce the contents of any given input to the 'average' calculation? It probably does. So it can be instructional to think through for yourself how you might prevent this attack.  For example, does your scenario need the concept of a session, that is invalidated upon enclave restart? 

Similarly, think about how request/response interactions have been modelled here. If an adversarial host were to replay messages, could it trick the enclave into thinking it had sent a particular response to a client when, in fact, the client had long-since received its response (from a previous execution of the enclave) and so was completely unaware that a new, and potentially different, response had now been generated?

R3 is working on an exemplar host process (and associated model for client interactions with enclaves) but you don't need to wait for that: get into the habit of thinking like the most paranoid person in the world! Imagine all the things a bad actor could do to ruin your day - or that of your customers - and you'll be well on your way to being a superstar confidential computing developer!  

See more about security here: https://docs.conclave.net/security.html
