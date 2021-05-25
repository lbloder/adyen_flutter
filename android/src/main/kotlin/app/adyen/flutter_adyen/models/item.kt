/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by arman on 10/12/2019.
 */

package app.adyen.flutter_adyen.models

data class LineItem(
    val quantity: Int?,
    val amountExcludingTax: Int?,
    val taxPercentage: Int?,
    val description: String?,
    // item id should be unique
    val id: String?,
    val amountIncludingTax: Int?,
    val taxCategory: String?
)