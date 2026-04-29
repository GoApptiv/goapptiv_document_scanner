import WeScan
import Flutter
import UIKit

public class SwiftGoapptivDocumentScannerPlugin: NSObject, FlutterPlugin {
    
    var result: FlutterResult?
    
    private var rootViewController: UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
            .first?
            .windows
            .first(where: { $0.isKeyWindow })?
            .rootViewController
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: Utils.channelName,
            binaryMessenger: registrar.messenger()
        )
        let instance = SwiftGoapptivDocumentScannerPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        self.result = result
        
        switch call.method {
        case "getPicture":
            camera()
        case "getPictureFromGallery":
            gallery()
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func camera() {
        guard let rootVC = rootViewController else {
            result?(FlutterError(code: "NO_ROOT_VC", message: "rootViewController is nil", details: nil))
            return
        }
        let scannerViewController = ImageScannerController()
        scannerViewController.imageScannerDelegate = self
        scannerViewController.modalPresentationStyle = .fullScreen
        if #available(iOS 13.0, *) {
            scannerViewController.overrideUserInterfaceStyle = .dark
        }
        rootVC.present(scannerViewController, animated: true)
    }
    
    private func gallery() {
        guard let rootVC = rootViewController else {
            result?(FlutterError(code: "NO_ROOT_VC", message: "rootViewController is nil", details: nil))
            return
        }
        let imagePicker = UIImagePickerController()
        imagePicker.delegate = self
        imagePicker.sourceType = .photoLibrary
        imagePicker.modalPresentationStyle = .fullScreen
        rootVC.present(imagePicker, animated: true)
    }
}

extension SwiftGoapptivDocumentScannerPlugin : ImageScannerControllerDelegate{
    
    public func imageScannerController(_ scanner: ImageScannerController, didFinishScanningWithResults results: ImageScannerResults) {
        scanner.dismiss(animated: true)
        let path = Utils.getScannedFile(results: results)
        result?(path)
    }
    
    public func imageScannerControllerDidCancel(_ scanner: ImageScannerController) {
        scanner.dismiss(animated: true)
    }
    
    public func imageScannerController(_ scanner: ImageScannerController, didFailWithError error: Error) {
        scanner.dismiss(animated: true)
    }
}

extension SwiftGoapptivDocumentScannerPlugin: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    public func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }
    
    public func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        
        guard let image = info[.originalImage] as? UIImage else { return }
        pikedCamera(image: image)
    }
    
    private func pikedCamera(image: UIImage? = nil){
        let scannerViewController: ImageScannerController = ImageScannerController(image:image)
        scannerViewController.imageScannerDelegate = self
        scannerViewController.modalPresentationStyle = .fullScreen

        if #available(iOS 13.0, *) {
            scannerViewController.overrideUserInterfaceStyle = .dark
        }
        rootViewController?.present(scannerViewController, animated:true, completion:nil)
    }
}
