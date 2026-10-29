package kmp.bootstrap.appkit.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSData
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.localTimeZone
import platform.Foundation.timeZoneWithName
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy

actual fun platformServices(): PlatformServices = IosPlatformServices()

/** iOS: dùng UIKit trực tiếp từ Kotlin/Native. Cần một view controller đang hiện để trình bày hộp thoại chia sẻ/chọn ảnh. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPlatformServices : PlatformServices {
    private fun top(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    override fun openUrl(url: String) { NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) } }
    override fun copyToClipboard(text: String) { UIPasteboard.generalPasteboard.string = text }

    override fun shareText(text: String): Boolean {
        val vc = top() ?: return false
        vc.presentViewController(UIActivityViewController(listOf(text), null), true, null); return true
    }

    private var delegate: PickerDelegate? = null

    override suspend fun pickImage(): PickedImage? {
        val vc = top() ?: return null
        val result = CompletableDeferred<PickedImage?>()
        val picker = UIImagePickerController().apply { sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary }
        delegate = PickerDelegate(result).also { picker.delegate = it }
        vc.presentViewController(picker, true, null)
        return result.await().also { delegate = null }
    }

    override fun timezoneId(): String = NSTimeZone.localTimeZone.name
}

@OptIn(ExperimentalForeignApi::class)
private class PickerDelegate(private val result: CompletableDeferred<PickedImage?>) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        picker.dismissViewControllerAnimated(true, null)
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val data = image?.let { UIImageJPEGRepresentation(it, 0.9) }
        result.complete(data?.let { PickedImage(it.toByteArray(), "image.jpg", "image/jpeg") })
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) { picker.dismissViewControllerAnimated(true, null); result.complete(null) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { bytes -> if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) } }
