package app.adyen.flutter_adyen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import app.adyen.flutter_adyen.models.*
import com.adyen.checkout.card.CardConfiguration
import com.adyen.checkout.components.model.PaymentMethodsApiResponse
import com.adyen.checkout.components.model.payments.Amount
import com.adyen.checkout.components.model.payments.request.PaymentComponentData
import com.adyen.checkout.components.model.payments.request.PaymentMethodDetails
import com.adyen.checkout.core.api.Environment
import com.adyen.checkout.core.log.LogUtil
import com.adyen.checkout.core.log.Logger
import com.adyen.checkout.core.util.LocaleUtil
import com.adyen.checkout.dropin.DropIn
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.service.DropInService
import com.adyen.checkout.dropin.service.DropInServiceResult
import com.adyen.checkout.redirect.RedirectComponent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
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
import java.io.IOException
import java.lang.reflect.Type
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.Map
import kotlin.collections.last

import io.flutter.plugin.common.BinaryMessenger


class FlutterAdyenPlugin() : MethodCallHandler, ActivityAware, FlutterPlugin, PluginRegistry.ActivityResultListener {
    var flutterResult: Result? = null

    private var activity: Activity? = null
    private var applicationContext: Context? = null
    private var methodChannel: MethodChannel? = null

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = FlutterAdyenPlugin()
            registrar.addActivityResultListener(instance)
            instance.onAttachedToEngine(registrar.context(), registrar.messenger());
        }
    }

    private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger) {
        this.applicationContext = applicationContext
        methodChannel = MethodChannel(messenger, "flutter_adyen")
        methodChannel?.setMethodCallHandler(this)
    }
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = null;
        methodChannel?.setMethodCallHandler(null);
        methodChannel = null;
    }

    override fun onMethodCall(call: MethodCall, res: Result) {
        val activity = this.activity

        if(activity == null) {
            res.error("ACTIVITY_NOT_BOUND", "Activity is null", "")
            return
        }

        when (call.method) {
            "openDropIn" -> {
                val additionalData = call.argument<Map<String, String>>("additionalData")
                val paymentMethods = call.argument<String>("paymentMethods")
                val baseUrl = call.argument<String>("baseUrl")
                val clientKey = call.argument<String>("clientKey")
                val amount = call.argument<String>("amount")
                val currency = call.argument<String>("currency")
                val env = call.argument<String>("environment")
                val lineItem = call.argument<String>("lineItem")
                val shopperReference = call.argument<String>("shopperReference")
                val headers = call.argument<Map<String, String>>("headers")
                val reference = call.argument<String>("reference")
                val merchantAccount = call.argument<String>("merchantAccount")

                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                val additionalDataString = additionalData?.let { JSONObject(additionalData).toString() }
                val localeString = call.argument<String>("locale") ?: "de_DE"
                val countryCode = localeString.split("_").last()
                val headersString = headers?.let { JSONObject(headers).toString() }

               val environment = when (env) {
                    "LIVE_US" -> Environment.UNITED_STATES
                    "LIVE_AUSTRALIA" -> Environment.AUSTRALIA
                    "LIVE_EUROPE" -> Environment.EUROPE
                    else -> Environment.TEST
                }

                try {
                    val jsonObject = JSONObject(paymentMethods ?: "")
                    val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(jsonObject)
                    print("##########")
                    print(localeString)
                    val shopperLocale = LocaleUtil.fromLanguageTag(localeString ?: "")
                    val cardConfiguration = CardConfiguration.Builder(activity, clientKey ?: "")
                            .setHolderNameRequired(true)
                            .setShopperLocale(shopperLocale)
                            .setEnvironment(environment)
                            .build()

                    val resultIntent = Intent(activity, activity::class.java)
                    resultIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP

                    val sharedPref = activity.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
                    with(sharedPref.edit()) {
                        remove("AdyenResultCode")
                        remove("AdyenPaymentInProgress")
                        putString("baseUrl", baseUrl)
                        putString("amount", "$amount")
                        putString("countryCode", countryCode)
                        putString("currency", currency)
                        putString("lineItem", lineItem)
                        putString("additionalData", additionalDataString)
                        putString("shopperReference", shopperReference)
                        putString("headers", headersString)
                        putString("reference", reference)
                        putString("merchantAccount", merchantAccount)
                        commit()
                    }

                    val dropInConfigurationBuilder = DropInConfiguration.Builder(activity, AdyenDropinService::class.java, clientKey ?: "")
                            .addCardConfiguration(cardConfiguration)
                            .setEnvironment(environment)

                    if (currency != null && amount != null) {
                        dropInConfigurationBuilder.setAmount(
                            Amount().apply {
                                this.value = amount.toInt()
                                this.currency = currency
                            }
                        )
                    }

                    val dropInConfiguration = dropInConfigurationBuilder.build()
                    DropIn.startPayment(activity, paymentMethodsApiResponse, dropInConfiguration)
                    flutterResult = res
                } catch (e: Throwable) {
                    Log.e("FlutterAdyenPlugin", e.message ?: "", e)
                    res.error("PAYMENT_ERROR", "${e.printStackTrace()}", "")
                }
            }
            else -> {
                res.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        Log.e("onActivityResult", "")
        val activity = this.activity
        val applicationContext = this.applicationContext

        if(activity == null || applicationContext == null) {
            flutterResult?.error("ACTIVITY_NOT_BOUND", "Activity is null", "")
            return false
        }

        val sharedPref = applicationContext.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val storedResultCode = sharedPref.getString("AdyenResultCode", "PAYMENT_CANCELLED")
        flutterResult?.success(storedResultCode)
        flutterResult = null;
        return true
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        this.activity = null
    }

}

inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object: TypeToken<T>() {}.type)


class AdyenDropinService : DropInService() {

    companion object {
        private val TAG = LogUtil.getTag()
    }

    override fun makePaymentsCall(paymentComponentJson: JSONObject): DropInServiceResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val paymentInProgress = sharedPref.getBoolean("AdyenPaymentInProgress", false)

        if (paymentInProgress) {
            return DropInServiceResult.Error("Payment in Progress")
        }

        with(sharedPref.edit()) {
            putBoolean("AdyenPaymentInProgress", true)
            commit()
        }

        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val amount = sharedPref.getString("amount", "UNDEFINED_STR")
        val currency = sharedPref.getString("currency", "UNDEFINED_STR")
        val countryCode = sharedPref.getString("countryCode", "DE")
        val lineItemString = sharedPref.getString("lineItem", "[]")
        val additionalDataString = sharedPref.getString("additionalData", null)
        val headersString = sharedPref.getString("headers", null)
        val uuid: UUID = UUID.randomUUID()
        val reference = sharedPref.getString("reference", uuid.toString())
        val shopperReference = sharedPref.getString("shopperReference", null)
        val merchantAccount = sharedPref.getString("merchantAccount", null) ?: ""

        val moshi = Moshi.Builder().build()
        val type: Type = Types.newParameterizedType(List::class.java, LineItem::class.java)
        val jsonAdapter: JsonAdapter<List<LineItem>> = moshi.adapter(type)
        val lineItems: List<LineItem>? = jsonAdapter.fromJson(lineItemString ?: "")

        val gson = Gson()

        val additionalData = additionalDataString?.let { gson.fromJson<Map<String, String>>(it) }
        val headers = headersString?.let { gson.fromJson<Map<String, String>>(it) }
        val serializedPaymentComponentData = PaymentComponentData.SERIALIZER.deserialize(paymentComponentJson)

        if (serializedPaymentComponentData.paymentMethod == null)
            DropInServiceResult.Error(reason = "Empty payment data")

        val paymentsRequest = createPaymentsRequest(lineItems, serializedPaymentComponentData, amount
                ?: "", currency ?: "", reference
                ?: "", shopperReference = shopperReference, countryCode = countryCode
                ?: "DE", additionalData = additionalData, merchantAccount = merchantAccount)
        val paymentsRequestJson = serializePaymentsRequest(paymentsRequest)

        val requestBody = RequestBody.create(MediaType.parse("application/json"), paymentsRequestJson.toString())

        val call = getService(headers ?: HashMap(), baseUrl ?: "").payments(requestBody)
        call.request().headers()
        return handleResponse(call)
    }

    override fun makeDetailsCall(actionComponentJson: JSONObject): DropInServiceResult {
        val gson = Gson()
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val requestBody = RequestBody.create(MediaType.parse("application/json"), actionComponentJson.toString())
        val headersString = sharedPref.getString("headers", null)
        val headers = headersString?.let { gson.fromJson<Map<String, String>>(it) }

        val call = getService(headers ?: HashMap(), baseUrl ?: "").details(requestBody)
        return handleResponse(call)
    }

    @Suppress("NestedBlockDepth")
    private fun handleResponse(call: Call<ResponseBody>): DropInServiceResult {
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
                    DropInServiceResult.Action(action)
                } else {
                    // TODO JsonUtils?
//                    Logger.d(TAG, "Final result - ${JsonUtils.indent(detailsResponse)}")

                    var content = "EMPTY"
                    if (detailsResponse.has("resultCode")) {
                        content = detailsResponse.get("resultCode").toString()
                    }
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", content)
                        commit()
                    }
                    Log.e("FINAL", content)
                    DropInServiceResult.Finished(content)
                }
            } else {
                Logger.e(TAG, "FAILED - ${response.message()}")
                with(sharedPref.edit()) {
                    putString("AdyenResultCode", "ERROR")
                    commit()
                }
                DropInServiceResult.Error(reason = "IOException")
            }
        } catch (e: IOException) {
            Logger.e(TAG, "IOException", e)
            with(sharedPref.edit()) {
                putString("AdyenResultCode", "ERROR")
                commit()
            }
            DropInServiceResult.Error(reason = "IOException")
        }
    }

    private fun createPaymentsRequest(lineItems: List<LineItem>?, paymentComponentData: PaymentComponentData<out PaymentMethodDetails>, amount: String, currency: String, reference: String, shopperReference: String?, countryCode: String, additionalData: Map<String, String>?, merchantAccount: String): PaymentsRequest {
        @Suppress("UsePropertyAccessSyntax")
        return PaymentsRequest(
            payment = Payment(paymentComponentData.getPaymentMethod() as PaymentMethodDetails,
                countryCode,
                paymentComponentData.isStorePaymentMethodEnable,
                getAmount(amount, currency),
                reference,
                RedirectComponent.getReturnUrl(applicationContext),
                lineItems = lineItems ?: emptyList(),
                shopperReference = shopperReference,
                merchantAccount = merchantAccount),
            additionalData = additionalData

        )
    }

    private fun getAmount(amount: String, currency: String) = createAmount(amount.toInt(), currency)
}

/*
package app.adyen.flutter_adyen

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.adyen.checkout.card.CardConfiguration
import com.adyen.checkout.components.model.PaymentMethodsApiResponse
import com.adyen.checkout.components.model.payments.Amount
import com.adyen.checkout.components.model.payments.request.PaymentComponentData
import com.adyen.checkout.components.model.payments.request.PaymentMethodDetails
import com.adyen.checkout.core.api.Environment
import com.adyen.checkout.dropin.DropIn
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.service.DropInService
import com.adyen.checkout.dropin.service.DropInServiceResult
import com.adyen.checkout.redirect.RedirectComponent
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.squareup.moshi.Moshi
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.Serializable
import com.google.gson.reflect.TypeToken;
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import java.util.*
import kotlin.collections.HashMap
import kotlin.jvm.Throws

class FlutterAdyenPlugin :
        MethodCallHandler, PluginRegistry.ActivityResultListener, FlutterPlugin, ActivityAware {

    private var methodChannel: MethodChannel? = null

    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    var flutterResult: Result? = null

    companion object {

        const val CHANNEL_NAME = "flutter_adyen"

        /**
         * For EmbeddingV1
         */
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            FlutterAdyenPlugin().apply {
                onAttachedToEngine(registrar.messenger())
                activity = registrar.activity()
                addActivityResultListener(registrar)
            }
        }
    }

    override fun onMethodCall(call: MethodCall, res: Result) {
        when (call.method) {
            "openDropIn" -> {

                if (activity == null) {
                    res.error("1",
                            "Activity is null",
                            "The activity is probably not attached")
                    return
                }

                val nonNullActivity = activity!!

                val additionalData = call.argument<Map<String, String>>("additionalData") ?: emptyMap()
                val paymentMethods = call.argument<String>("paymentMethods")
                val baseUrl = call.argument<String>("baseUrl")
                val clientKey = call.argument<String>("clientKey")
                val amount = call.argument<String>("amount")
                val currency = call.argument<String>("currency")
                val env = call.argument<String>("environment")
                val lineItem = call.argument<Map<String, String>>("lineItem")
                val shopperReference = call.argument<String>("shopperReference")

                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                val lineItemString = JSONObject(lineItem).toString()
                val additionalDataString = JSONObject(additionalData).toString()
                val localeString = call.argument<String>("locale") ?: "de_DE"
                val countryCode = localeString.split("_").last()

                /*
                Log.e("[Flutter Adyen]", "Client Key from Flutter: $clientKey")
                Log.e("[Flutter Adyen]", "Environment from Flutter: $env")
                Log.e("[Flutter Adyen]", "Locale String from Flutter: $localeString")
                Log.e("[Flutter Adyen]", "Locale String from Flutter: $paymentMethods")
                Log.e("[Flutter Adyen]", "Country Code from Flutter: $countryCode")
                Log.e("[Flutter Adyen]", "Base URL from Flutter: $baseUrl")
                Log.e("[Flutter Adyen]", "Currency from Flutter: $currency")
                Log.e("[Flutter Adyen]", "Shopper Reference from Flutter: $shopperReference")
                 */

                val environment = when (env) {
                    "LIVE_US" -> Environment.UNITED_STATES
                    "LIVE_AUSTRALIA" -> Environment.AUSTRALIA
                    "LIVE_EUROPE" -> Environment.EUROPE
                    else -> Environment.TEST
                }

                // Log.e("[Flutter Adyen] ENVIRONMENT", "Resolved environment: $environment")

                try {
                    val jsonObject = JSONObject(paymentMethods ?: "")
                    val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(jsonObject)
                    val shopperLocale = Locale.GERMANY
                    // val shopperLocale = if (LocaleUtil.isValidLocale(locale)) locale else LocaleUtil.getLocale(nonNullActivity)
                    // Log.e("[Flutter Adyen] SHOPPER LOCALE", "Shopper Locale from localeString $localeString: $shopperLocale")
                    val cardConfiguration = CardConfiguration.Builder(nonNullActivity, clientKey!!)
                            .setHolderNameRequired(true)
                            .setShopperLocale(shopperLocale)
                            .setEnvironment(environment)
                            .build()

                    val sharedPref = nonNullActivity.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
                    with(sharedPref.edit()) {
                        remove("AdyenResultCode")
                        putString("baseUrl", baseUrl)
                        putString("amount", "$amount")
                        putString("countryCode", countryCode)
                        putString("currency", currency)
                        putString("lineItem", lineItemString)
                        putString("additionalData", additionalDataString)
                        putString("shopperReference", shopperReference)
                        commit()
                    }

                    val dropInConfiguration = DropInConfiguration.Builder(nonNullActivity, AdyenDropinService::class.java, clientKey)
                            .addCardConfiguration(cardConfiguration)
                            .setEnvironment(environment)
                            .build()

                    DropIn.startPayment(nonNullActivity, paymentMethodsApiResponse, dropInConfiguration)

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

    //region lifecycle
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (activity == null) return false

        val sharedPref = activity!!.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val storedResultCode = sharedPref.getString("AdyenResultCode", "PAYMENT_CANCELLED")
        flutterResult?.success(storedResultCode)
        flutterResult = null
        return true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.binaryMessenger)
    }

    private fun onAttachedToEngine(messenger: BinaryMessenger) {
        this.methodChannel = MethodChannel(messenger, CHANNEL_NAME)
        this.methodChannel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        unbindActivityBinding()
        this.methodChannel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        bindActivityBinding(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        unbindActivityBinding()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        bindActivityBinding(binding)
    }

    override fun onDetachedFromActivity() {
        unbindActivityBinding()
    }

    private fun bindActivityBinding(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        this.activityBinding = binding
        addActivityResultListener(binding)
    }

    private fun unbindActivityBinding() {
        activityBinding?.removeActivityResultListener(this)
        this.activity = null
        this.activityBinding = null
    }

    private fun addActivityResultListener(activityBinding: ActivityPluginBinding) {
        activityBinding.addActivityResultListener(this)
    }

    private fun addActivityResultListener(registrar: PluginRegistry.Registrar) {
        registrar.addActivityResultListener(this)
    }
    //endregion
}

/**
 * This is just an example on how to make network calls on the [DropInService].
 * You should make the calls to your own servers and have additional data or processing if necessary.
 */
@Throws(JsonSyntaxException::class)
inline fun <reified T> Gson.fromJson(json: String): T? = fromJson<T>(json, object: TypeToken<T>() {}.type)

class AdyenDropinService : DropInService() {

    override fun makePaymentsCall(paymentComponentJson: JSONObject): DropInServiceResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val amount = sharedPref.getString("amount", "UNDEFINED_STR")
        val currency = sharedPref.getString("currency", "UNDEFINED_STR")
        val countryCode = sharedPref.getString("countryCode", "DE")
        val lineItemString = sharedPref.getString("lineItem", "UNDEFINED_STR")
        val additionalDataString = sharedPref.getString("additionalData", "UNDEFINED_STR")
        val uuid: UUID = UUID.randomUUID()
        val reference: String = uuid.toString()
        val shopperReference = sharedPref.getString("shopperReference", null)

        val moshi = Moshi.Builder().build()
        val jsonAdapter = moshi.adapter(LineItem::class.java)
        val lineItem: LineItem? = jsonAdapter.fromJson(lineItemString ?: "")

        val gson = Gson()

        val additionalData = gson.fromJson<Map<String, String>>(additionalDataString ?: "") ?: emptyMap()
        val serializedPaymentComponentData = PaymentComponentData.SERIALIZER.deserialize(paymentComponentJson)

        if (serializedPaymentComponentData.paymentMethod == null)
            return DropInServiceResult.Error(errorMessage = "Empty payment data")

        val paymentsRequest = createPaymentsRequest(
                context = this@AdyenDropinService, lineItem, serializedPaymentComponentData,
                amount = amount ?: "",
                currency = currency ?: "",
                reference = reference,
                shopperReference = shopperReference,
                countryCode = countryCode ?: "DE",
                additionalData = additionalData)
        val paymentsRequestJson = serializePaymentsRequest(paymentsRequest)

        val requestBody = RequestBody.create(MediaType.parse("application/json"), paymentsRequestJson.toString())

        val headers: HashMap<String, String> = HashMap()
        val call = getService(headers, baseUrl ?: "").payments(requestBody)
        call.request().headers()
        return try {
            val response = call.execute()
            val paymentsResponse = response.body()

            if (response.isSuccessful && paymentsResponse != null) {
                if (paymentsResponse.action != null) {
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", paymentsResponse.action.toString())
                        commit()
                    }
                    DropInServiceResult.Action(paymentsResponse.action)
                } else {
                    if (paymentsResponse.resultCode != null &&
                            (paymentsResponse.resultCode == "Authorised" || paymentsResponse.resultCode == "Received" || paymentsResponse.resultCode == "Pending")) {
                        with(sharedPref.edit()) {
                            putString("AdyenResultCode", paymentsResponse.resultCode)
                            commit()
                        }
                       DropInServiceResult.Finished(paymentsResponse.resultCode)
                    } else {
                        with(sharedPref.edit()) {
                            putString("AdyenResultCode", paymentsResponse.resultCode ?: "EMPTY")
                            commit()
                        }
                       DropInServiceResult.Finished(paymentsResponse.resultCode ?: "EMPTY")
                    }
                }
            } else {
                with(sharedPref.edit()) {
                    putString("AdyenResultCode", "ERROR")
                    commit()
                }
                DropInServiceResult.Error(errorMessage = "IOException")
            }
        } catch (e: IOException) {
            with(sharedPref.edit()) {
                putString("AdyenResultCode", "ERROR")
                commit()
            }
            DropInServiceResult.Error(errorMessage = "IOException")
        }
    }

    override fun makeDetailsCall(actionComponentJson: JSONObject): DropInServiceResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val requestBody = RequestBody.create(MediaType.parse("application/json"), actionComponentJson.toString())
        val headers: HashMap<String, String> = HashMap()

        val call = getService(headers, baseUrl ?: "").details(requestBody)
        return try {
            val response = call.execute()
            val detailsResponse = response.body()
            if (response.isSuccessful && detailsResponse != null) {
                if (detailsResponse.action != null) {
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", detailsResponse.action.toString())
                        commit()
                    }
                    DropInServiceResult.Action(detailsResponse.action)
                }
                else if (detailsResponse.resultCode != null &&
                        (detailsResponse.resultCode == "Authorised" || detailsResponse.resultCode == "Received" || detailsResponse.resultCode == "Pending")) {
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", detailsResponse.resultCode)
                        commit()
                    }
                    DropInServiceResult.Finished(detailsResponse.resultCode)
                } else {
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", detailsResponse.resultCode ?: "EMPTY")
                        commit()
                    }
                    DropInServiceResult.Finished(detailsResponse.resultCode ?: "EMPTY")
                }
            } else {
                with(sharedPref.edit()) {
                    putString("AdyenResultCode", "ERROR")
                    commit()
                }
                DropInServiceResult.Error(errorMessage = "IOException")
            }
        } catch (e: IOException) {
            with(sharedPref.edit()) {
                putString("AdyenResultCode", "ERROR")
                commit()
            }
            DropInServiceResult.Error(errorMessage =  "IOException")
        }
    }
}

fun createPaymentsRequest(context: Context, lineItem: LineItem?,
                          paymentComponentData: PaymentComponentData<out PaymentMethodDetails>,
                          amount: String, currency: String,
                          reference: String, shopperReference: String?,
                          countryCode: String,
                          additionalData: Map<String, String>): PaymentsRequest {
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

fun createAmount(value: Int, currency: String): Amount {
    val amount = Amount()
    amount.currency = currency
    amount.value = value
    return amount
}

//region data classes
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
        val additionalData: Map<String, String>
): Serializable

data class LineItem(
        val id: String,
        val description: String
): Serializable

data class AdditionalData(val allow3DS2: String = "true")
//endregion

private fun serializePaymentsRequest(paymentsRequest: PaymentsRequest): JSONObject {
    val gson = Gson()
    val jsonString = gson.toJson(paymentsRequest)
    val request = JSONObject(jsonString)
    print(request)
    return request
}

 */