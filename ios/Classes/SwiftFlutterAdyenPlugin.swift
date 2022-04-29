import Flutter
import UIKit
import Adyen
import Adyen3DS2
import Foundation
import AdyenNetworking

struct PaymentError: Error {

}
struct PaymentCancelled: Error {

}
public class SwiftFlutterAdyenPlugin: NSObject, FlutterPlugin {

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "flutter_adyen", binaryMessenger: registrar.messenger())
        let instance = SwiftFlutterAdyenPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    var dropInComponent: DropInComponent?
    var baseURL: String?
    var authToken: String?
    var merchantAccount: String?
    var clientKey: String?
    var currency: String?
    var amount: String?
    var returnUrl: String?
    var reference: String?
    var mResult: FlutterResult?
    var topController: UIViewController?
    var environment: String?
    var shopperReference: String?
    var lineItemString: String?
    var shopperLocale: String?
    var additionalData:  [String: String]?
    var headers:  [String: String]?

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard call.method.elementsEqual("openDropIn") else { return }

        let arguments = call.arguments as? [String: Any]
        let paymentMethodsResponse = arguments?["paymentMethods"] as? String
        let rawBaseURL = arguments?["baseUrl"] as? String
        merchantAccount = arguments?["merchantAccount"] as? String
        additionalData = arguments?["additionalData"] as? [String: String]
        clientKey = arguments?["clientKey"] as? String
        currency = arguments?["currency"] as? String
        amount = arguments?["amount"] as? String
        lineItemString = arguments?["lineItem"] as? String
        environment = arguments?["environment"] as? String
        reference = arguments?["reference"] as? String
        returnUrl = arguments?["returnUrl"] as? String
        shopperReference = arguments?["shopperReference"] as? String
        shopperLocale = String((arguments?["locale"] as? String)?.split(separator: "_").last ?? "DE")
        headers = arguments?["headers"] as? [String: String]
        mResult = result

        guard let rawUrl = rawBaseURL else {
            return
        }

        if rawUrl.last == "/" {
            baseURL = String(rawUrl.dropLast())
        } else {
            baseURL = rawUrl
        }

        guard let paymentData = paymentMethodsResponse?.data(using: .utf8),
              let paymentMethods = try? JSONDecoder().decode(PaymentMethods.self, from: paymentData) else {
            return
        }

        var ctx = Environment.test
        if(environment == "LIVE_US") {
            ctx = Environment.liveUnitedStates
        } else if (environment == "LIVE_AUSTRALIA"){
            ctx = Environment.liveAustralia
        } else if (environment == "LIVE_EUROPE"){
            ctx = Environment.liveEurope
        }

        let dropInComponentStyle = DropInComponent.Style()

        let apiContext = APIContext(environment: ctx, clientKey: clientKey!)
        let configuration = DropInComponent.Configuration(apiContext: apiContext);
        configuration.card.showsHolderNameField = true
        if let paymentCurrency = currency, let amountString = amount, let paymentAmount = Int(amountString) {
            configuration.payment = Adyen.Payment(amount: Adyen.Amount(value: paymentAmount, currencyCode: paymentCurrency), countryCode: shopperLocale ?? "DE")
        }
        dropInComponent = DropInComponent(paymentMethods: paymentMethods, configuration: configuration, style: dropInComponentStyle)

        dropInComponent?.delegate = self


        if var topController = UIApplication.shared.keyWindow?.rootViewController, let dropIn = dropInComponent {
            self.topController = topController
            while let presentedViewController = topController.presentedViewController{
                topController = presentedViewController
            }
            topController.present(dropIn.viewController, animated: true)
        }
    }
}

extension SwiftFlutterAdyenPlugin: DropInComponentDelegate {

    public func didComplete(from component: DropInComponent) {
        component.stopLoadingIfNeeded()
    }

    public func didCancel(component: PaymentComponent, from dropInComponent: DropInComponent) {
        self.didFail(with: PaymentCancelled(), from: dropInComponent)
    }

    public func didSubmit(_ data: PaymentComponentData, for paymentMethod: PaymentMethod, from component: DropInComponent) {
        guard let baseURL = baseURL, let url = URL(string: baseURL + "/payments") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        headers?.forEach { key, value in request.addValue(value, forHTTPHeaderField: key) }
        let amountAsInt = Int(amount ?? "0")
        // prepare json data
        let paymentMethod = data.paymentMethod.encodable
        let lineItemJsonData = lineItemString?.data(using: .utf8)
        let lineItems = try? JSONDecoder().decode([LineItem].self, from: lineItemJsonData ?? Data())

        if lineItems == nil {
            self.didFail(with: PaymentError(), from: component)
            return
        }
        let paymentRequest = PaymentRequest(
                payment: Payment(
                        paymentMethod: paymentMethod,
                        lineItems: lineItems ?? [],
                        currency: currency ?? "",
                        amount: amountAsInt ?? 0,
                        returnUrl: returnUrl ?? "",
                        storePayment: data.storePaymentMethod,
                        shopperReference: shopperReference,
                        reference: reference,
                        countryCode: shopperLocale,
                        merchantAccount: merchantAccount ?? ""),
                additionalData:additionalData ?? [String: String]())

        do {
            let jsonData = try JSONEncoder().encode(paymentRequest)

            request.httpBody = jsonData
            URLSession.shared.dataTask(with: request) { data, response, error in
                if let data = data {
                    self.finish(data: data, component: component)
                }
                if error != nil {
                    self.didFail(with: PaymentError(), from: component)
                }
            }.resume()
        } catch {
            didFail(with: PaymentError(), from: component)
        }

    }

    func finish(data: Data, component: DropInComponent) {
        DispatchQueue.main.async {
            guard let response = try? JSONDecoder().decode(PaymentsResponse.self, from: data) else {
                self.didFail(with: PaymentError(), from: component)
                component.stopLoadingIfNeeded()
                return
            }
            if let action = response.action {
                component.handle(action)
            } else {
                if response.resultCode == .authorised || response.resultCode == .received || response.resultCode == .pending, let result = self.mResult {
                    result(response.resultCode.rawValue)
                    self.topController?.dismiss(animated: false, completion: nil)

                } else if (response.resultCode == .error || response.resultCode == .refused) {
                    self.didFail(with: PaymentError(), from: component)
                }
                else {
                    self.didFail(with: PaymentCancelled(), from: component)
                }
                // Stop loading when finishing due to success or error
                component.stopLoadingIfNeeded()
            }
        }
    }

    public func didProvide(_ data: ActionComponentData, from component: DropInComponent) {
        guard let baseURL = baseURL, let url = URL(string: baseURL + "payments/details") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        headers?.forEach { key, value in request.addValue(value, forHTTPHeaderField: key) }
        let detailsRequest = DetailsRequest(paymentData: data.paymentData ?? "", details: data.details.encodable)
        do {
            let detailsRequestData = try JSONEncoder().encode(detailsRequest)
            request.httpBody = detailsRequestData
            URLSession.shared.dataTask(with: request) { data, response, error in
                if let response = response as? HTTPURLResponse {
                    if (response.statusCode != 200) {
                        self.didFail(with: PaymentError(), from: component)
                    }
                }
                if let data = data {
                    self.finish(data: data, component: component)
                }

            }.resume()
        } catch {
            self.didFail(with: PaymentError(), from: component)
        }
    }

    public func didFail(with error: Error, from component: DropInComponent) {
        DispatchQueue.main.async {
            if (error is PaymentCancelled) {
                self.mResult?("PAYMENT_CANCELLED")
            } else if let componentError = error as? ComponentError, componentError == ComponentError.cancelled {
                self.mResult?("PAYMENT_CANCELLED")
            }else {
                self.mResult?("PAYMENT_ERROR")
            }
            self.topController?.dismiss(animated: true, completion: nil)
        }
    }
}

struct DetailsRequest: Encodable {
    let paymentData: String
    let details: AnyEncodable
}

struct PaymentRequest : Encodable {
    let payment: Payment
    let additionalData: [String: String]
}

struct Payment : Encodable {
    let paymentMethod: AnyEncodable
    let lineItems: [LineItem]
    let channel: String = "iOS"
    let additionalData = ["allow3DS2" : "true", "executeThreeD" : "true"] //TODO should executeThreeD be set by default?
    let amount: Amount
    let reference: String
    let returnUrl: String
    let storePaymentMethod: Bool
    let shopperReference: String?
    let countryCode: String?
    let merchantAccount: String

    init(paymentMethod: AnyEncodable, lineItems: [LineItem], currency: String, amount: Int, returnUrl: String, storePayment: Bool, shopperReference: String?, reference: String?, countryCode: String?, merchantAccount: String) {
        self.paymentMethod = paymentMethod
        self.lineItems = lineItems
        self.amount = Amount(currency: currency, value: amount)
        self.returnUrl = returnUrl
        self.shopperReference = shopperReference
        self.storePaymentMethod = storePayment
        self.countryCode = countryCode
        self.merchantAccount = merchantAccount
        self.reference = reference ?? UUID().uuidString
    }
}

struct LineItem: Codable {
    let quantity: Int?
    let amountExcludingTax: Int?
    let taxPercentage: Int?
    let description: String?
    let id: String?
    let amountIncludingTax: Int?
    let taxCategory: String?
}

struct Amount: Codable {
    let currency: String
    let value: Int
}

internal struct PaymentsResponse: Response {

    internal let resultCode: ResultCode

    internal let action: Action?

    internal init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.resultCode = try container.decode(ResultCode.self, forKey: .resultCode)
        self.action = try container.decodeIfPresent(Action.self, forKey: .action)
    }

    private enum CodingKeys: String, CodingKey {
        case resultCode
        case action
    }

}

internal extension PaymentsResponse {

    // swiftlint:disable:next explicit_acl
    enum ResultCode: String, Decodable {
        case authorised = "Authorised"
        case refused = "Refused"
        case pending = "Pending"
        case cancelled = "Cancelled"
        case error = "Error"
        case received = "Received"
        case redirectShopper = "RedirectShopper"
        case identifyShopper = "IdentifyShopper"
        case challengeShopper = "ChallengeShopper"
        case presentToShopper = "PresentToShopper"
    }

}
