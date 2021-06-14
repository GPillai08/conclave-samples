package com.r3.conclave.samples.tribuo.enclave

import com.r3.conclave.enclave.Enclave
import com.r3.conclave.mail.EnclaveMail
import com.r3.conclave.samples.tribuo.common.TribuoTask
import com.r3.conclave.samples.tribuo.common.format

class TribuoEnclave : Enclave() {
    /**
     * Deserialize the request sent by the client, execute it and mail the serialized result.
     * @param id An opaque identifier for the mail.
     * @param mail Access to the decrypted/authenticated mail body+envelope.
     * @param routingHint ignored
     */
    override fun receiveMail(id: Long, mail: EnclaveMail, routingHint: String?) {
        val data = String(mail.bodyAsBytes)
        val task = format.decodeFromString(TribuoTask.serializer(), data)
        sendMail(mail, task.execute())
    }

    /**
     * Sends a mail to the client.
     * @param mail Access to the decrypted/authenticated mail body+envelope.
     * @param body content to place in the mail body.
     */
    private fun sendMail(mail: EnclaveMail, body: ByteArray) {
        val reply: ByteArray = postOffice(mail).encryptMail(body)
        postMail(reply, null)
    }
}