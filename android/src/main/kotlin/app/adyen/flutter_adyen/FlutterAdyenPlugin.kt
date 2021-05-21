package app.adyen.flutter_adyen

import Payment
import PaymentsRequest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import app.adyen.flutter_adyen.models.LineItem
import com.adyen.checkout.base.model.PaymentMethodsApiResponse
import com.adyen.checkout.base.model.payments.request.*
import com.adyen.checkout.card.CardConfiguration
import com.adyen.checkout.core.api.Environment
import com.adyen.checkout.core.log.LogUtil
import com.adyen.checkout.core.log.Logger
import com.adyen.checkout.core.model.JsonUtils
import com.adyen.checkout.core.util.LocaleUtil
import com.adyen.checkout.dropin.DropIn
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.service.CallResult
import com.adyen.checkout.dropin.service.DropInService
import com.adyen.checkout.redirect.RedirectComponent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.squareup.moshi.Moshi
import createAmount
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import serializePaymentsRequest
import java.io.IOException
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.Map
import kotlin.collections.last
import kotlin.collections.listOf


class FlutterAdyenPlugin(private val activity: Activity) : MethodCallHandler, PluginRegistry.ActivityResultListener {
    var flutterResult: Result? = null

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "flutter_adyen")
            val plugin = FlutterAdyenPlugin(registrar.activity())
            channel.setMethodCallHandler(plugin)
            registrar.addActivityResultListener(plugin)
        }
    }

    override fun onMethodCall(call: MethodCall, res: Result) {
        when (call.method) {
            "openDropIn" -> {

                val additionalData = call.argument<Map<String, String>>("additionalData")
                val paymentMethods = call.argument<String>("paymentMethods")
                val baseUrl = call.argument<String>("baseUrl")
                val clientKey = call.argument<String>("clientKey")
                val publicKey = call.argument<String>("publicKey")
                val amount = call.argument<String>("amount")
                val currency = call.argument<String>("currency")
                val env = call.argument<String>("environment")
                val lineItem = call.argument<Map<String, String>>("lineItem")
                val shopperReference = call.argument<String>("shopperReference")
                val headers = call.argument<Map<String, String>>("headers")

                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                val lineItemString = JSONObject(lineItem).toString()
                val additionalDataString = additionalData?.let { JSONObject(additionalData).toString() }
                val localeString = call.argument<String>("locale") ?: "de_DE"
                val countryCode = localeString.split("_").last()
                val headersString = headers?.let { JSONObject(headers).toString() }

                var environment = Environment.TEST
                if (env == "LIVE_US") {
                    environment = Environment.UNITED_STATES
                } else if (env == "LIVE_AUSTRALIA") {
                    environment = Environment.AUSTRALIA
                } else if (env == "LIVE_EUROPE") {
                    environment = Environment.EUROPE
                }

                try {
                    val jsonObject = JSONObject(paymentMethods ?: "")
                    val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(jsonObject)
                    val shopperLocale = LocaleUtil.fromLanguageTag(localeString ?: "")
                    val cardConfiguration = CardConfiguration.Builder(activity)
                            .setHolderNameRequire(true)
                            .setPublicKey(publicKey ?: "")
                            .setShopperLocale(shopperLocale)
                            .setEnvironment(environment)
                            .build()

                    val resultIntent = Intent(activity, activity::class.java)
                    resultIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP

                    val sharedPref = activity.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
                    with(sharedPref.edit()) {
                        remove("AdyenResultCode")
                        putString("baseUrl", baseUrl)
                        putString("amount", "$amount")
                        putString("countryCode", countryCode)
                        putString("currency", currency)
                        putString("lineItem", lineItemString)
                        putString("additionalData", additionalDataString)
                        putString("shopperReference", shopperReference)
                        putString("headers", headersString)
                        commit()
                    }

                    val dropInConfiguration = DropInConfiguration.Builder(activity, resultIntent, AdyenDropinService::class.java)
                            .setClientKey(clientKey ?: "")
                            .addCardConfiguration(cardConfiguration)
                            .build()
                    DropIn.startPayment(activity, paymentMethodsApiResponse, dropInConfiguration)
                    flutterResult = res
                } catch (e: Throwable) {
                    res.error("PAYMENT_ERROR", "${e.printStackTrace()}", "")
                }


            }
            else -> {
                res.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val sharedPref = activity.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val storedResultCode = sharedPref.getString("AdyenResultCode", "PAYMENT_CANCELLED")
        flutterResult?.success(storedResultCode)
        flutterResult = null;
        return true
    }

}

/**
 * This is just an example on how to make network calls on the [DropInService].
 * You should make the calls to your own servers and have additional data or processing if necessary.
 */
inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object: TypeToken<T>() {}.type)


class AdyenDropinService : DropInService() {

    companion object {
        private val TAG = LogUtil.getTag()
    }

    override fun makePaymentsCall(paymentComponentData: JSONObject): CallResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val amount = sharedPref.getString("amount", "UNDEFINED_STR")
        val currency = sharedPref.getString("currency", "UNDEFINED_STR")
        val countryCode = sharedPref.getString("countryCode", "DE")
        val lineItemString = sharedPref.getString("lineItem", "UNDEFINED_STR")
        val additionalDataString = sharedPref.getString("additionalData", null)
        val headersString = sharedPref.getString("headers", null)
        val uuid: UUID = UUID.randomUUID()
        val reference: String = uuid.toString()
        val shopperReference = sharedPref.getString("shopperReference", null)

        val moshi = Moshi.Builder().build()
        val jsonAdapter = moshi.adapter(LineItem::class.java)
        val lineItem: LineItem? = jsonAdapter.fromJson(lineItemString ?: "")

        val gson = Gson()

        val additionalData = additionalDataString?.let { gson.fromJson<Map<String, String>>(it) } ?: emptyMap()
        val headers = headersString?.let { gson.fromJson<Map<String, String>>(it) }
        val serializedPaymentComponentData = PaymentComponentData.SERIALIZER.deserialize(paymentComponentData)

        if (serializedPaymentComponentData.paymentMethod == null)
            return CallResult(CallResult.ResultType.ERROR, "Empty payment data")

        val paymentsRequest = createPaymentsRequest(this@AdyenDropinService, lineItem, serializedPaymentComponentData, amount
                ?: "", currency ?: "", reference
                ?: "", shopperReference = shopperReference, countryCode = countryCode
                ?: "DE", additionalData = additionalData)
        val paymentsRequestJson = serializePaymentsRequest(paymentsRequest)

        val requestBody = RequestBody.create(MediaType.parse("application/json"), paymentsRequestJson.toString())

        val call = getService(headers ?: HashMap(), baseUrl ?: "").payments(requestBody)
        call.request().headers()
        return handleResponse(call)
    }

    override fun makeDetailsCall(actionComponentData: JSONObject): CallResult {
        val gson = Gson()
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val requestBody = RequestBody.create(MediaType.parse("application/json"), actionComponentData.toString())
        val headersString = sharedPref.getString("headers", null)
        val headers = headersString?.let { gson.fromJson<Map<String, String>>(it) }

        val call = getService(headers ?: HashMap(), baseUrl ?: "").details(requestBody)
        return handleResponse(call)
    }

    @Suppress("NestedBlockDepth")
    private fun handleResponse(call: Call<ResponseBody>): CallResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)

        return try {
            val response = call.execute()

            val byteArray = response.errorBody()?.bytes()
            if (byteArray != null) {
                Logger.e(TAG, "errorBody - ${String(byteArray)}")
            }

            if (response.isSuccessful) {
                val detailsResponse = JSONObject(response.body()?.string() ?: "")
                if (detailsResponse.has("action") && !detailsResponse.isNull("action")) {

                    val action = detailsResponse.get("action").toString()
                    Log.e("ACTION", action)
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", action)
                        commit()
                    }
                    CallResult(CallResult.ResultType.ACTION, action)
                } else {
                    Logger.d(TAG, "Final result - ${JsonUtils.indent(detailsResponse)}")

                    var content = "EMPTY"
                    if (detailsResponse.has("resultCode")) {
                        content = detailsResponse.get("resultCode").toString()
                    }
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", content)
                        commit()
                    }
                    Log.e("FINAL", content)
                    CallResult(CallResult.ResultType.FINISHED, content)
                }
            } else {
                Logger.e(TAG, "FAILED - ${response.message()}")
                with(sharedPref.edit()) {
                    putString("AdyenResultCode", "ERROR")
                    commit()
                }
                CallResult(CallResult.ResultType.ERROR, "IOException")
            }
        } catch (e: IOException) {
            Logger.e(TAG, "IOException", e)
            with(sharedPref.edit()) {
                putString("AdyenResultCode", "ERROR")
                commit()
            }
            CallResult(CallResult.ResultType.ERROR, "IOException")
        }
    }
}


fun createPaymentsRequest(context: Context, lineItem: LineItem?, paymentComponentData: PaymentComponentData<out PaymentMethodDetails>, amount: String, currency: String, reference: String, shopperReference: String?, countryCode: String, additionalData: Map<String, String>): PaymentsRequest {
    @Suppress("UsePropertyAccessSyntax")
    return PaymentsRequest(
            payment = Payment(paymentComponentData.getPaymentMethod() as PaymentMethodDetails,
                    countryCode,
                    paymentComponentData.isStorePaymentMethodEnable,
                    getAmount(amount, currency),
                    reference,
                    RedirectComponent.getReturnUrl(context),
                    lineItems = listOf(lineItem),
                    shopperReference = shopperReference),
            additionalData = additionalData

    )
}

private fun getAmount(amount: String, currency: String) = createAmount(amount.toInt(), currency)
