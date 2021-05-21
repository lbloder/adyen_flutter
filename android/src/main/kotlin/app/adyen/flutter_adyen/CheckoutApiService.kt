/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by caiof on 11/2/2019.
 */

package app.adyen.flutter_adyen

import android.util.Log
import com.adyen.checkout.base.model.paymentmethods.InputDetail
import com.adyen.checkout.base.model.payments.request.*
import com.adyen.checkout.base.model.payments.response.*
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import okhttp3.*
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface CheckoutApiService {
    @POST("payments")
    fun payments(@Body paymentsRequest: RequestBody): Call<ResponseBody>

    @POST("payments/details")
    fun details(@Body detailsRequest: RequestBody): Call<ResponseBody>
}

class HeaderInterceptor(private val headers: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.run {
        val builder = request().newBuilder()
        headers.keys.forEach { builder.addHeader(it, headers[it] ?: "") }
        proceed(builder.build())
    }
}

fun getService(headers: Map<String, String>, baseUrl: String): CheckoutApiService {
    val converter = MoshiConverterFactory.create()

    val client = OkHttpClient.Builder().addInterceptor(HeaderInterceptor(headers)).build()

    val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(converter)
            .addCallAdapterFactory(CoroutineCallAdapterFactory())
            .client(client)
            .build()

    return retrofit.create(CheckoutApiService::class.java)
}