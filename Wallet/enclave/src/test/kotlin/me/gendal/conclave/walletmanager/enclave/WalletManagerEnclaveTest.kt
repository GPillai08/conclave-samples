package me.gendal.conclave.walletmanager.enclave

import com.r3.conclave.common.EnclaveInstanceInfo
import com.r3.conclave.common.SHA256Hash
import com.r3.conclave.host.EnclaveHost
import com.r3.conclave.host.MailCommand
import com.r3.conclave.mail.Curve25519PrivateKey
import com.r3.conclave.mail.PostOffice
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import me.gendal.conclave.walletmanager.common.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicLong

@ExperimentalSerializationApi
class WalletManagerEnclaveTest {

    private lateinit var enclave: EnclaveHost
    private lateinit var attestation: EnclaveInstanceInfo

    private val inboxes = HashMap<String, MutableList<ByteArray>>()
    private val keys = hashMapOf<String, Curve25519PrivateKey>()
    private val postOffices = HashMap<String, PostOffice>()

    private val idCounter = AtomicLong()

    @BeforeEach
    fun startup() {
        enclave = EnclaveHost.load("me.gendal.conclave.walletmanager.enclave.WalletManagerEnclave")
        enclave.start(null) { commands: List<MailCommand?> ->
            for (command in commands) {
                if (command is MailCommand.PostMail) {
                    synchronized(inboxes) {
                        val inbox = inboxes.computeIfAbsent(command.routingHint!!) { ArrayList() }
                        inbox += command.encryptedBytes
                    }
                }
            }
        }
        attestation = enclave.enclaveInstanceInfo

        for(actor in listOf("alice", "bob", "charley", "denise")){
            val key = Curve25519PrivateKey.random()
            val po = attestation.createPostOffice(key, "${actor}Topic")
            keys[actor] = key
            postOffices[actor] = po
        }
    }

    @Test
    fun `Cannot create non-quorate computations`() {
        val oneParticipantRequest = SetupComputation(
            Computation(
                "InvalidComputation1",
                Computation.ComputationType.key,
                listOf(keys["alice"]!!.publicKey),
                1
            )
        )
        var response = enclaveRequest(oneParticipantRequest, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.QUORUM_NOT_REACHED, response[0].responseCode)

        // should fail
        val multipleParticipantsQuorum0Request = SetupComputation(
            Computation(
                "InvalidComputation2",
                Computation.ComputationType.key,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey),
                0
            )
        )
        response = enclaveRequest(multipleParticipantsQuorum0Request, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.QUORUM_NOT_REACHED, response[0].responseCode)

        // should succeed. quorum of 1 is ok
        val multipleParticipantsQuorum1Request = SetupComputation(
            Computation(
                "InvalidComputation3",
                Computation.ComputationType.key,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey),
                1
            )
        )
        response = enclaveRequest(multipleParticipantsQuorum1Request, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, response[0].responseCode)

        // should fail... not enough participants to make quorum
        val validQuorumInsufficientParticipantsRequest = SetupComputation(
            Computation(
                "InvalidComputation4",
                Computation.ComputationType.key,
                listOf(keys["alice"]!!.publicKey),
                2
            )
        )
        response = enclaveRequest(validQuorumInsufficientParticipantsRequest, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.QUORUM_NOT_REACHED, response[0].responseCode)
    }

    @Test
    fun `Cannot participate in computations you're not part of`() {

        val setupArbitraryComputation = SetupComputation(
            Computation(
                "ArbitraryComputation",
                Computation.ComputationType.key,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey),
                1
            )
        )
        var response = enclaveRequest(setupArbitraryComputation, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, response[0].responseCode)

        val attemptToSubmit = SubmitValue(
            "ArbitraryComputation",
            Submission("100")
        )
        // should work
        response = enclaveRequest(attemptToSubmit, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, response[0].responseCode)
        // should fail
        response = enclaveRequest(attemptToSubmit, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.NOT_AUTHORISED, response[0].responseCode)

        val visibleComputations = ListComputations
        // should work
        val listResponse = enclaveRequest(visibleComputations, Computations.serializer(), postOffices["alice"]!!)
        assert(listResponse.size == 1)
        assertSame(ResponseCode.SUCCESS, listResponse[0].responseCode)
        // should fail
        val listResponse2 = enclaveRequest(visibleComputations, Computations.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.NO_RESULTS, listResponse2[0].responseCode)
    }

    @Test
    fun `Simple Avg Calculations actually work`() {
        /* TODO
        *
        * Check avg/min/max, including for edge cases like zero, negative numbers and MIN/MAX submissions
        * Check we handle malformed inputs (we're using Strings taken from command line)
        * Check the IDENTITY of the 'winner' is returned, not the value
        *
         */
        /**
         * avg 1
         * 1. setup a computation for avg with 2 participants - alice, bob
         * 2. Submit a different value for alice, bob
         * 3. Submit a GetResults
         * 4. Assert the value with the correct one
         **/
        val setupAverageComputation = SetupComputation(
            Computation(
                "AverageComputation",
                Computation.ComputationType.avg,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey, keys["charley"]!!.publicKey),
                2
            )
        )
        val responseSetup = enclaveRequest(setupAverageComputation, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseSetup[0].responseCode)

        val submissionAlice = "100"
        val submissionMessageForAlice  = "Submission from Alice"
        val submitValueForAlice = SubmitValue("AverageComputation", Submission(submissionAlice, submissionMessageForAlice))
        val responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

        val submissionBob = "200"
        val submissionMessageForBob  = "Submission from Bob"
        val submitValueForBob = SubmitValue("AverageComputation", Submission(submissionBob, submissionMessageForBob))
        val responseForSubmissionByBob = enclaveRequest(submitValueForBob, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByBob[0].responseCode)

        val getComputationResult = GetComputationResult("AverageComputation")
        val responseForGetComputationResult = enclaveRequest(getComputationResult, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForGetComputationResult[0].responseCode)
        assertTrue("150.0" == responseForGetComputationResult[0].message)
    }

    @Test
    fun `Min Calculations actually work`() {
      /* TODO
      *
      * Check avg/min/max, including for edge cases like zero, negative numbers and MIN/MAX submissions
      * Check we handle malformed inputs (we're using Strings taken from command line)
      * Check the IDENTITY of the 'winner' is returned, not the value
      *
       */
        /**
         * avg 1
         * 1. setup a computation for avg with 3 participants - alice, bob and charley
         * 2. Submit a different value for alice, bob and charley
         * 3. Submit a GetResults
         * 4. Assert the value with the correct one
         **/
        val setupMinComputation = SetupComputation(
            Computation(
                "MinComputation",
                Computation.ComputationType.min,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey, keys["charley"]!!.publicKey),
                3
            )
        )
        val responseSetup = enclaveRequest(setupMinComputation, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseSetup[0].responseCode)

        val submissionAlice = "100"
        val submissionMessageForAlice  = "Submission from Alice"
        val submitValueForAlice = SubmitValue("MinComputation", Submission(submissionAlice, submissionMessageForAlice))
        val responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

        val submissionBob = "200"
        val submissionMessageForBob  = "Submission from Bob"
        val submitValueForBob = SubmitValue("MinComputation", Submission(submissionBob, submissionMessageForBob))
        val responseForSubmissionByBob = enclaveRequest(submitValueForBob, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByBob[0].responseCode)

        val submissionCharley = "300"
        val submissionMessageForCharley  = "Submission from Charley"
        val submitValueForCharley = SubmitValue("MinComputation", Submission(submissionCharley, submissionMessageForCharley))
        val responseForSubmissionByCharley = enclaveRequest(submitValueForCharley, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByCharley[0].responseCode)

        val getComputationResult = GetComputationResult("MinComputation")
        val responseForGetComputationResult = enclaveRequest(getComputationResult, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForGetComputationResult[0].responseCode)
        assertTrue(keys["alice"]!!.publicKey.toString()==(responseForGetComputationResult[0].message))
    }

    @Test
    fun `Locking of results works`() {
        /* TODO
        *
        * Confirm that a calculation cannot be locked before quorum
        * Confirm that a calculation IS locked first time a results request after quorum is reached
        * Confirm that once a calculation is locked no new results can be added
        * Confirm that this does NOT apply to KeyMatcher
        *
         */
    }


    @Test
    fun `Confirm that a calculation cannot be locked before quorum`() {
        /* TODO
        *
        * Confirm that a calculation cannot be locked before quorum
        * Confirm that a calculation IS locked first time a results request after quorum is reached
        * Confirm that once a calculation is locked no new results can be added
        * Confirm that this does NOT apply to KeyMatcher
        *
         */
        val setupMaxComputation = SetupComputation(
            Computation(
                "MaxComputation",
                Computation.ComputationType.max,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey, keys["charley"]!!.publicKey),
                3
            )
        )
        val responseSetup = enclaveRequest(setupMaxComputation, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseSetup[0].responseCode)

        val submissionAlice = "-10"
        val submissionMessageForAlice  = "Submission from Alice"
        val submitValueForAlice = SubmitValue("MaxComputation", Submission(submissionAlice, submissionMessageForAlice))
        val responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

        val submissionBob = "0"
        val submissionMessageForBob  = "Submission from Bob"
        val submitValueForBob = SubmitValue("MaxComputation", Submission(submissionBob, submissionMessageForBob))
        val responseForSubmissionByBob = enclaveRequest(submitValueForBob, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByBob[0].responseCode)

        val submissionCharley = "300"
        val submissionMessageForCharley  = "Submission from Charley"
        val submitValueForCharley = SubmitValue("MaxComputation", Submission(submissionCharley, submissionMessageForCharley))
        val responseForSubmissionByCharley = enclaveRequest(submitValueForCharley, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByCharley[0].responseCode)

        val getComputationResult = GetComputationResult("MaxComputation")
        val responseForGetComputationResult = enclaveRequest(getComputationResult, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForGetComputationResult[0].responseCode)
        assertTrue(keys["charley"]!!.publicKey.toString() == responseForGetComputationResult[0].message)
    }

    @Test
    fun `Confirm that a calculation IS locked first time a results request after quorum is reached`() {
        /* TODO
        *
        * Confirm that a calculation IS locked first time a results request after quorum is reached
        * Confirm that once a calculation is locked no new results can be added
        * Confirm that this does NOT apply to KeyMatcher
        *
         */
        val setupMaxComputation = SetupComputation(
            Computation(
                "MaxComputation",
                Computation.ComputationType.max,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey, keys["charley"]!!.publicKey),
                3
            )
        )
        val responseSetup = enclaveRequest(setupMaxComputation, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseSetup[0].responseCode)

        var submissionAlice = "-10"
        var submissionMessageForAlice  = "Submission from Alice"
        var submitValueForAlice = SubmitValue("MaxComputation", Submission(submissionAlice, submissionMessageForAlice))
        var responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

        val submissionBob = "0"
        val submissionMessageForBob  = "Submission from Bob"
        val submitValueForBob = SubmitValue("MaxComputation", Submission(submissionBob, submissionMessageForBob))
        val responseForSubmissionByBob = enclaveRequest(submitValueForBob, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByBob[0].responseCode)

        val submissionCharley = "300"
        val submissionMessageForCharley  = "Submission from Charley"
        val submitValueForCharley = SubmitValue("MaxComputation", Submission(submissionCharley, submissionMessageForCharley))
        val responseForSubmissionByCharley = enclaveRequest(submitValueForCharley, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByCharley[0].responseCode)

        val getComputationResult = GetComputationResult("MaxComputation")
        val responseForGetComputationResult = enclaveRequest(getComputationResult, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForGetComputationResult[0].responseCode)
        assertTrue(keys["charley"]!!.publicKey.toString() == responseForGetComputationResult[0].message)

         submissionAlice = "400"
         submissionMessageForAlice  = "Submission from Alice"
         submitValueForAlice = SubmitValue("MaxComputation", Submission(submissionAlice, submissionMessageForAlice))
         responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.COMPUTATION_LOCKED, responseForSubmissionByAlice[0].responseCode)
    }

    @Test
    fun `Confirm that once a calculation is locked no new results can be added`() {
        /* TODO
        *
        * Confirm that once a calculation is locked no new results can be added
        * Confirm that this does NOT apply to KeyMatcher
        *
         */
        val setupMaxComputation = SetupComputation(
            Computation(
                "MaxComputation",
                Computation.ComputationType.max,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey, keys["charley"]!!.publicKey),
                3
            )
        )
        val responseSetup = enclaveRequest(setupMaxComputation, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseSetup[0].responseCode)

        var submissionAlice = "-10"
        var submissionMessageForAlice  = "Submission from Alice"
        var submitValueForAlice = SubmitValue("MaxComputation", Submission(submissionAlice, submissionMessageForAlice))
        var responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

         submissionAlice = "400"
         submissionMessageForAlice  = "Submission from Alice"
         submitValueForAlice = SubmitValue("MaxComputation", Submission(submissionAlice, submissionMessageForAlice))
         responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

        val submissionBob = "0"
        val submissionMessageForBob  = "Submission from Bob"
        val submitValueForBob = SubmitValue("MaxComputation", Submission(submissionBob, submissionMessageForBob))
        val responseForSubmissionByBob = enclaveRequest(submitValueForBob, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByBob[0].responseCode)

        val submissionCharley = "300"
        val submissionMessageForCharley  = "Submission from Charley"
        val submitValueForCharley = SubmitValue("MaxComputation", Submission(submissionCharley, submissionMessageForCharley))
        val responseForSubmissionByCharley = enclaveRequest(submitValueForCharley, EnclaveMessageResponse.serializer(), postOffices["charley"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByCharley[0].responseCode)

        val getComputationResult = GetComputationResult("MaxComputation")
        val responseForGetComputationResult = enclaveRequest(getComputationResult, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForGetComputationResult[0].responseCode)
        assertTrue(keys["charley"]!!.publicKey.toString() == responseForGetComputationResult[0].message)


    }

    @Test
    fun `Key matched logic works`() {
        /* TODO
        *
        * Revalidate quorum logic (different code path in enclave)
        * Confirm only submitters of a key can see what other submitters of same key have submitted
        * Confirm all commentary messages are visible
        * Confirm that locking does not take place
        *
         */

    }

    @Test
    fun `Confirm only submitters of a key can see what other submitters of same key have submitted`() {
        val setupKeyComputation = SetupComputation(
            Computation(
                "KeyComputation",
                Computation.ComputationType.key,
                listOf(keys["alice"]!!.publicKey, keys["bob"]!!.publicKey, keys["charley"]!!.publicKey),
                2
            )
        )
        val responseSetup = enclaveRequest(setupKeyComputation, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseSetup[0].responseCode)

        val submissionAlice = "KEY-Ganesh"
        val submissionMessageForAlice  = "Submission from Alice"
        val submitValueForAlice = SubmitValue("KeyComputation", Submission(submissionAlice, submissionMessageForAlice))
        val responseForSubmissionByAlice = enclaveRequest(submitValueForAlice, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByAlice[0].responseCode)

        val submissionBob = "Key-BOB"
        val submissionMessageForBob  = "Submission from Bob"
        val submitValueForBob = SubmitValue("KeyComputation", Submission(submissionBob, submissionMessageForBob))
        val responseForSubmissionByBob = enclaveRequest(submitValueForBob, EnclaveMessageResponse.serializer(), postOffices["bob"]!!)
        assertSame(ResponseCode.SUCCESS, responseForSubmissionByBob[0].responseCode)

        val getComputationResult = GetComputationResult("KeyComputation")
        val responseForGetComputationResult = enclaveRequest(getComputationResult, EnclaveMessageResponse.serializer(), postOffices["alice"]!!)
        assertSame(ResponseCode.CHECK_INBOX, responseForGetComputationResult[0].responseCode)
        // assertTrue(keys["alice"]!!.publicKey.toString().equals(responseForGetComputationResult[0].message))

        inbox(
            "KeyMatch" + SHA256Hash.hash(keys["alice"]!!.publicKey.encoded).toString(),
            postOffices["alice"]!!,
            KeyMatcherResult.serializer()
        )
        println('1')


    }

    @Test
    fun `Last-submitted versus all-submitted logic works`() {
        /* TODO
        *
        * For avg/min/max, submitters can revise their submissions prior to locking. For Key Match, *all* submissions should be used
        * Confirm that it is their final submission (and ONLY their final submission) that is used for calcs
        * Confirm that locking works correctly
        * Confirm that quorum is calculated correctly (unique contributors, not unique submissions)
        *
         */
    }

    @AfterEach
    fun shutdown() {
        enclave.close()
    }

    private fun <T : EnclaveResponse> enclaveRequest(
        request: ClientRequest,
        responseSerializer: KSerializer<T>,
        postOffice: PostOffice,
        correlationId: String = UUID.randomUUID().toString()
    ): List<T> {
        deliverMail(request,postOffice, correlationId)
        return inbox(correlationId, postOffice, responseSerializer)
    }

    private fun deliverMail(request: ClientRequest, postOffice: PostOffice, correlationId: String) {
        val requestBody = ProtoBuf.encodeToByteArray(ClientRequest.serializer(), request)
        val requestMail = postOffice.encryptMail(requestBody)
        enclave.deliverMail(idCounter.getAndIncrement(), requestMail, correlationId)
    }

    private fun <T : EnclaveResponse> inbox(correlationId: String, postOffice: PostOffice, serializer: KSerializer<T>): List<T> {
        return synchronized(inboxes) {
            inboxes[correlationId]!!.map {
                val responseBytes = postOffice.decryptMail(it).bodyAsBytes
                ProtoBuf.decodeFromByteArray(serializer, responseBytes)
            }
        }
    }
}