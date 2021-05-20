/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by arman on 10/12/2019.
 */

package app.adyen.flutter_adyen.models

import java.util.Date

data class LineItem(
    val quantity: Int = 2,
    val amountExcludingTax: Int = 100,
    val taxPercentage: Int = 0,
    val description: String = "Coffee",
    // item id should be unique
    val id: String = Date().time.toString(),
    val amountIncludingTax: Int = 100,
    val taxCategory: String = "Low"
)