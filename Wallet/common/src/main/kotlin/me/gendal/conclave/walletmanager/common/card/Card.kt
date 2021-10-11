package me.gendal.conclave.walletmanager.common.card

import java.util.*

val CVC_LENGTH_MAX : Int = 4
val CVC_LENGTH_MIN : Int = 3

val CARD_NUMBER_LENGTH_MAX : Int = 19
val CARD_NUMBER_LENGTH_MIN : Int = 12

val CARD_EXP_MONTH_MAX : Int = 12
val CARD_EXP_MONTH_MIN : Int = 1

val CARD_EXP_YEAR_LENGTH = 4

val CURRENT_MONTH : Int = Calendar.getInstance().get(Calendar.MONTH)
val CURRENT_YEAR : Int = Calendar.getInstance().get(Calendar.YEAR)

/**
 * Regular expressions were taken from this site: https://www.regular-expressions.info/creditcard.html
 * and https://stackoverflow.com/questions/72768/how-do-you-detect-credit-card-type-based-on-number/72801
 */
sealed class CardType(val pattern: String) {
    object Visa : CardType("^4[0-9]{6,}\$")
    object MasterCard : CardType("^5[1-5][0-9]{5,}|222[1-9][0-9]{3,}|22[3-9][0-9]{4,}|2[3-6][0-9]{5,}|27[01][0-9]{4,}|2720[0-9]{3,}\$")
    object AmericanExpress : CardType("^3[47][0-9]{5,}\$")
    object DinersClub : CardType("^3(?:0[0-5]|[68][0-9])[0-9]{4,}\$")
    object Discover : CardType("^6(?:011|5[0-9]{2})[0-9]{3,}\$")
    object Jcb : CardType("^(?:2131|1800|35[0-9]{3})[0-9]{3,}\$")
}

class CardUtil {

    companion object {
        private val LOG_TAG = CardUtil::class.java.simpleName
        private val SUPPORTED_CARDS : MutableList<CardType> = getAllCardTypes()

        private var isLoggingEnabled : Boolean = true

        private fun getAllCardTypes() = mutableListOf(
            CardType.Visa,
            CardType.MasterCard,
            CardType.AmericanExpress,
            CardType.DinersClub,
            CardType.Discover,
            CardType.Jcb)

        /*
        * Reference link: https://www.geeksforgeeks.org/luhn-algorithm/
        * This method checks the validity of a given raw card number via Luhn algorithm/modulus 10
        */
        private fun isValidCardNumberByLuhn(stringInputCardNumber: String): Boolean {
            var sum = 0
            var isSecondDigit = false

            for (i in stringInputCardNumber.length - 1 downTo 0) {
                var d = stringInputCardNumber[i] - '0'

                if (isSecondDigit) {
                    d = d * 2
                }

                sum += d / 10
                sum += d % 10

                isSecondDigit = !isSecondDigit
            }

            val result : Boolean = ((sum % 10) == 0)
            printLog("isValidCardNumber By Luhn () = $result")
            return result
        }

        private fun isValidCardNumberByTypeSupport(stringInputCardNumber: String) : Boolean {
            for (supportedType in SUPPORTED_CARDS) {
                if (stringInputCardNumber.matches(supportedType.pattern.toRegex())) {
                    printLog("isValidCardNumber By Type Support() = true")
                    return true
                }
            }

            printLog("isValidCardNumber By Type Support () = false")
            return false
        }

        private fun isValidCardNumberLength(inputCardNumber: String) : Boolean {
            val result : Boolean =
                ((inputCardNumber.length >= CARD_NUMBER_LENGTH_MIN) &&
                        (inputCardNumber.length <= CARD_NUMBER_LENGTH_MAX))

            printLog("isValidCardNumber Length () = $result")
            return result
        }

        private fun isValidCardNumberValue(inputCardNumber: Long) : Boolean =
            (inputCardNumber > 0)

        private fun printLog(logMessage : String) {
            if(isLoggingEnabled) println("$LOG_TAG.$logMessage")
        }

        private fun setLoggingEnabled(isEnabled : Boolean) {
            isLoggingEnabled = isEnabled
        }

        private fun setSupportedCardTypes(listSupportedCardTypes : List<CardType>) {
            if(listSupportedCardTypes.isEmpty()) {
                printLog("setSupportedCardTypes() : Cannot set empty selection of CardTypes.")
            }

            else {
                SUPPORTED_CARDS.clear()
                SUPPORTED_CARDS.addAll(listSupportedCardTypes)
            }
        }

        fun setUp(isLoggingEnabled : Boolean, listSupportedCardTypes : List<CardType>) {
            setLoggingEnabled(isLoggingEnabled)
            setSupportedCardTypes(listSupportedCardTypes)
        }

        fun isValidCard(inputCardNumber: Long, inputCVC: Int,
                        inputExpMonth: Int, inputExpYear: Int) : Boolean =
            (isValidCardNumber(inputCardNumber) && isValidCVC(inputCVC) &&
                    isValidExpirationDate(inputExpMonth, inputExpYear))

        /*
        * @param inputCardNumber : the 12-19 digit number
        * */
        fun isValidCardNumber(inputCardNumber : Long) : Boolean {
            printLog("isValidCardNumber() : input = $inputCardNumber")
            return isValidCardNumberValue(inputCardNumber) &&
                    isValidCardNumberLength(inputCardNumber.toString()) &&
                    isValidCardNumberByTypeSupport(inputCardNumber.toString()) &&
                    isValidCardNumberByLuhn(inputCardNumber.toString())
        }

        /*
        * @param inputCVC : 3-4 digit CVC/CVV value
        * */
        fun isValidCVC(inputCVC : Int) : Boolean {
            val stringInputCVC = inputCVC.toString()
            val result : Boolean = ((stringInputCVC.length >= CVC_LENGTH_MIN) &&
                    (stringInputCVC.length <= CVC_LENGTH_MAX))
            printLog("isValidCVC() : $stringInputCVC = $result")

            return result
        }

        /*
        * @param inputExpMonth : non-zero based month value. Range of 1-12
        * @param inputExpYear : 4-digit year value
        * */
        fun isValidExpirationDate(inputExpMonth : Int, inputExpYear: Int) : Boolean {
            val isValidMonthRange =
                ((inputExpMonth >= CARD_EXP_MONTH_MIN) &&
                        (inputExpMonth <= CARD_EXP_MONTH_MAX))
            val isValidYearValue = (inputExpYear > 0)
            val isValidYearLength = (inputExpYear.toString().length == CARD_EXP_YEAR_LENGTH)

            val isFutureYear = (inputExpYear > CURRENT_YEAR)
            val isSameYear_FutureOrCurrentMonth =
                ((inputExpYear == CURRENT_YEAR) && (inputExpMonth >= CURRENT_MONTH))

            val result = ((isValidMonthRange && isValidYearLength && isValidYearValue) &&
                    (isFutureYear || isSameYear_FutureOrCurrentMonth))

            printLog("isValidExpirationDate() : $inputExpMonth / $inputExpYear = $result")
            return result
        }

    }

}
