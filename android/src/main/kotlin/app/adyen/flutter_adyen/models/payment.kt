package app.adyen.flutter_adyen.models

import app.adyen.flutter_adyen.models.LineItem
import com.adyen.checkout.base.model.payments.Amount
import com.adyen.checkout.base.model.payments.request.PaymentMethodDetails
import com.google.gson.Gson
import org.json.JSONObject
import java.io.Serializable

fun createAmount(value: Int, currency: String): Amount {
    val amount = Amount()
    amount.currency = currency
    amount.value = value
    return amount
}

data class Payment(
        val paymentMethod: PaymentMethodDetails,
        val countryCode: String = "DE",
        val storePaymentMethod: Boolean,
        val amount: Amount,
        val reference: String,
        val returnUrl: String,
        val channel: String = "Android",
        val lineItems: List<LineItem?>,
        val additionalData: AdditionalData = AdditionalData(allow3DS2 = "true"),
        val shopperReference: String?
): Serializable

data class PaymentsRequest(
        val payment: Payment,
        val additionalData: Map<String, String>?
): Serializable

data class AdditionalData(val allow3DS2: String = "true")

fun serializePaymentsRequest(paymentsRequest: PaymentsRequest): JSONObject {

    val gson = Gson()
    val jsonString = gson.toJson(paymentsRequest)
    val request = JSONObject(jsonString)
    print(request)
    return request
}