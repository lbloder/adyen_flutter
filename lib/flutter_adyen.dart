import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

class FlutterAdyen {
  static const MethodChannel _channel = const MethodChannel('flutter_adyen');

  static Future<String> openDropIn(
      {paymentMethods,
      String baseUrl,
      String clientKey,
      String publicKey,
      List<LineItem> lineItem,
      String locale,
      String amount,
      String currency,
      String returnUrl,
      String shopperReference,
      Map<String, String> additionalData,
      Map<String, String> headers,
      String reference,
      String merchantAccount,
      environment = 'TEST'}) async {
    Map<String, dynamic> args = {};
    args.putIfAbsent('paymentMethods', () => paymentMethods);
    args.putIfAbsent('additionalData', () => additionalData);
    args.putIfAbsent('baseUrl', () => baseUrl);
    args.putIfAbsent('clientKey', () => clientKey);
    args.putIfAbsent('publicKey', () => publicKey);
    args.putIfAbsent('amount', () => amount);
    args.putIfAbsent('locale', () => locale);
    args.putIfAbsent('currency', () => currency);
    args.putIfAbsent('lineItem', () => jsonEncode(lineItem));
    args.putIfAbsent('returnUrl', () => returnUrl);
    args.putIfAbsent('environment', () => environment);
    args.putIfAbsent('shopperReference', () => shopperReference);
    args.putIfAbsent('headers', () => headers);
    args.putIfAbsent('reference', () => reference ?? Uuid().v4().toString());
    args.putIfAbsent('merchantAccount', () => merchantAccount);

    final String response = await _channel.invokeMethod('openDropIn', args);
    return response;
  }
}

class LineItem {
  final int quantity;
  final int amountExcludingTax;
  final int taxPercentage;
  final String description;
  final String id;
  final int amountIncludingTax;
  final String taxCategory;

  LineItem({
    this.quantity,
    this.amountExcludingTax,
    this.amountIncludingTax,
    this.taxPercentage,
    this.taxCategory: "Low",
    this.description,
    this.id
  });

  Map<String, dynamic> toJson() {
    return {
      'quantity': quantity,
      'amountExcludingTax': amountExcludingTax,
      'amountIncludingTax': amountIncludingTax,
      'taxPercentage': taxPercentage,
      'taxCategory': taxCategory,
      'description': description,
      'id': id,
    };
  }
}
