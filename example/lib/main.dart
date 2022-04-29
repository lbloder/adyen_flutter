import 'dart:convert';

import 'package:adyen_dropin/flutter_adyen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'mock_data.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String? _payment_result = 'Unknown';
  String? dropInResponse;

  // String _payment_result = 'Unknown';
  // String dropInResponse = "";

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        floatingActionButton: FloatingActionButton(
          child: Icon(Icons.add),
          onPressed: () async {
            try {
              dropInResponse = await FlutterAdyen.openDropIn(
                  merchantAccount: '<Your Merchant Account>',
                  reference: '<Your payment reference>',
                  paymentMethods: jsonEncode(examplePaymentMethods),
                  baseUrl: 'https://yourdomain.com',
                  clientKey: 'clientkey',
                  locale: 'de_DE',
                  shopperReference: 'asdasda',
                  returnUrl: 'http://asd.de',
                  amount: '1230',
                  lineItem: [LineItem(
                    quantity: 2,
                    amountIncludingTax: 15,
                    id: "1",
                    description: "Product",
                    taxPercentage: 20,
                  )],
                  currency: 'EUR',
                  additionalData: {});
            } on PlatformException catch (e) {
              if (e.code == 'PAYMENT_CANCELLED')
                dropInResponse = 'Payment Cancelled';
              else
                dropInResponse = 'Payment Error';
            }

            setState(() {
              _payment_result = dropInResponse;
            });
          },
        ),
        appBar: AppBar(
          title: const Text('Flutter Adyen'),
        ),
        body: Center(
          child: Text('Payment Result: $_payment_result\n'),
        ),
      ),
    );
  }
}
