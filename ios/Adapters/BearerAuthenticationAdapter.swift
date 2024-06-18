import Foundation
import Alamofire

@objc public class BearerAuthenticationAdapter: NSObject, RequestAdapter {
    @objc public static func addAuthorizationBearerToken(to urlRequest: URLRequest, withSessionBaseUrlString sessionBaseUrlString: String) -> URLRequest {
        var urlRequest = urlRequest
        do {
            if let bearerToken = try Keychain.getToken(for: sessionBaseUrlString) {
                urlRequest.headers.add(.authorization(bearerToken: bearerToken))
            }
        } catch {
            NotificationCenter.default.post(name: Notification.Name(API_CLIENT_EVENTS["CLIENT_ERROR"]!),
                                            object: nil,
                                            userInfo: ["serverUrl": sessionBaseUrlString, "errorCode": error._code, "errorDescription": error.localizedDescription])
        }
        
        return urlRequest
    }

    public func adapt(_ urlRequest: URLRequest, for session: Session, completion: @escaping (Result<URLRequest, Error>) -> Void) {
        let urlRequest = BearerAuthenticationAdapter.addAuthorizationBearerToken(to: urlRequest, withSessionBaseUrlString: session.baseUrl.absoluteString)

        completion(.success(urlRequest))
    }
}
