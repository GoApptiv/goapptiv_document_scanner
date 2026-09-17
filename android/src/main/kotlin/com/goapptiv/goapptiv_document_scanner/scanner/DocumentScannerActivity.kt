package com.goapptiv.goapptiv_document_scanner.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import com.goapptiv.goapptiv_document_scanner.R
import com.goapptiv.goapptiv_document_scanner.scanner.constants.DefaultSetting
import com.goapptiv.goapptiv_document_scanner.scanner.constants.DocumentScannerExtra
import com.goapptiv.goapptiv_document_scanner.scanner.constants.ImageProvider
import com.goapptiv.goapptiv_document_scanner.scanner.extensions.move
import com.goapptiv.goapptiv_document_scanner.scanner.extensions.onClick
import com.goapptiv.goapptiv_document_scanner.scanner.extensions.saveToFile
import com.goapptiv.goapptiv_document_scanner.scanner.extensions.screenHeight
import com.goapptiv.goapptiv_document_scanner.scanner.extensions.screenWidth
import com.goapptiv.goapptiv_document_scanner.scanner.models.Document
import com.goapptiv.goapptiv_document_scanner.scanner.models.Quad
import com.goapptiv.goapptiv_document_scanner.scanner.ui.ImageCropView
import com.goapptiv.goapptiv_document_scanner.scanner.utils.CameraUtil
import com.goapptiv.goapptiv_document_scanner.scanner.utils.FileUtil
import com.goapptiv.goapptiv_document_scanner.scanner.utils.GalleryUtil
import com.goapptiv.goapptiv_document_scanner.scanner.utils.ImageUtil
import java.io.File
import org.opencv.core.Point
import java.io.FileOutputStream

/**
 * This class contains the main document scanner code. It opens the camera, lets the user
 * take a photo of a document (homework paper, business card, etc.), detects document corners,
 * allows user to make adjustments to the detected corners, depending on options, and saves
 * the cropped document. It allows the user to do this for 1 or more documents.
 *
 * @constructor creates document scanner activity
 */
class DocumentScannerActivity : AppCompatActivity() {
    /**
     * @property maxNumDocuments maximum number of documents a user can scan at a time
     */
    private var maxNumDocuments = DefaultSetting.MAX_NUM_DOCUMENTS

    /**
     * @property letUserAdjustCrop whether or not to let user move automatically detected corners
     */
    private var letUserAdjustCrop = DefaultSetting.LET_USER_ADJUST_CROP

    /**
     * @property croppedImageQuality the 0 - 100 quality of the cropped image
     */
    private var croppedImageQuality = DefaultSetting.CROPPED_IMAGE_QUALITY

    /**
     * @property imageProvider whether to use the camera or gallery to choose documents
     */
    private var imageProvider = DefaultSetting.IMAGE_PROVIDER

    /**
     * @property cropperOffsetWhenCornersNotFound if we can't find document corners, we set
     * corners to image size with a slight margin
     */
    private val cropperOffsetWhenCornersNotFound = 100.0

    /**
     * @property document This is the current document. Initially it's null. Once we capture
     * the photo, and find the corners we update document.
     */
    private var document: Document? = null
    private  lateinit var photo:Bitmap

    /**
     * @property documents a list of documents (original photo file path, original photo
     * dimensions and 4 corner points)
     */
    private val documents = mutableListOf<Document>()

    private var filePath:String =""

    /**
     * @property cameraUtil gets called with photo file path once user takes photo, or
     * exits camera
     */
    private val cameraUtil = CameraUtil(
        this,
        onPhotoCaptureSuccess = {
            // user takes photo
            originalPhotoPath ->

            // if maxNumDocuments is 3 and this is the 3rd photo, hide the new photo button since
            // we reach the allowed limit
            if (documents.size == maxNumDocuments - 1) {
//                val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
//                newPhotoButton.isClickable = false
//                newPhotoButton.visibility = View.INVISIBLE
            }
            filePath=originalPhotoPath

            // get bitmap from photo file path
            try {
                photo = ImageUtil().getImageFromFilePath(originalPhotoPath)
            } catch (exception: Exception) {
                finishIntentWithError("unable to load image: ${exception.message}")
                return@CameraUtil
            }

            // get document corners by detecting them, or falling back to photo corners with
            // slight margin if we can't find the corners
            val corners = try {
                val (topLeft, topRight, bottomLeft, bottomRight) =  if(letUserAdjustCrop) getDefaultDocumentCorners(photo) else getWholeDocumentCorners(photo)
                Quad(topLeft, topRight, bottomRight, bottomLeft)
            } catch (exception: Exception) {
                val (topLeft, topRight, bottomLeft, bottomRight) =  getDefaultDocumentCorners(photo)
                Quad(topLeft, topRight, bottomRight, bottomLeft)
            }

            document = Document(originalPhotoPath, photo.width, photo.height,corners)

            if (letUserAdjustCrop) {
                // user is allowed to move corners to make corrections
                try {
                    // set preview image height based off of photo dimensions
                    imageView.setImagePreviewBounds(photo, screenWidth, screenHeight)

                    // display original photo, so user can adjust detected corners
                    imageView.setImage(photo)

                    // document corner points are in original image coordinates, so we need to
                    // scale and move the points to account for blank space (caused by photo and
                    // photo container having different aspect ratios)
                    val cornersInImagePreviewCoordinates = corners
                        .mapOriginalToPreviewImageCoordinates(
                            imageView.imagePreviewBounds,
                            imageView.imagePreviewBounds.height() / photo.height
                        )

                    // display cropper, and allow user to move corners
                    imageView.setCropper(cornersInImagePreviewCoordinates)
                } catch (exception: Exception) {
                    finishIntentWithError(
                        "unable get image preview ready: ${exception.message}"
                    )
                    return@CameraUtil
                }
            } else {
                // user isn't allowed to move corners, so accept automatically detected corners
                document?.let { document ->
                    documents.add(document)
                }

                // create cropped document image, and return file path to complete document scan
                cropDocumentAndFinishIntent()
            }
        },
        onCancelPhoto = {
            // user exits camera
            // complete document scan if this is the first document since we can't go to crop view
            // until user takes at least 1 photo
            if (documents.isEmpty()) {
                onClickCancel()
            }
        }
    )

    /**
     * @property galleryUtil gets called with photo file path once user chooses image, or
     * exits gallery
     */
    private val galleryUtil = GalleryUtil(
        this,
        onGallerySuccess = {
            // user chooses photo
            originalPhotoPath ->

            // if maxNumDocuments is 3 and this is the 3rd photo, hide the new photo button since
            // we reach the allowed limit
            if (documents.size == maxNumDocuments - 1) {
//                val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
//                newPhotoButton.isClickable = false
//                newPhotoButton.visibility = View.INVISIBLE
            }

            filePath=originalPhotoPath

            // get bitmap from photo file path
            try {
                photo = ImageUtil().getImageFromFilePath(originalPhotoPath)
            } catch (exception: Exception) {
                finishIntentWithError("unable to load image: ${exception.message}")
                return@GalleryUtil
            }

            // get document corners by detecting them, or falling back to photo corners with
            // slight margin if we can't find the corners
            val corners = try {
                val (topLeft, topRight, bottomLeft, bottomRight) = if(letUserAdjustCrop) getDefaultDocumentCorners(photo) else getWholeDocumentCorners(photo)
                Quad(topLeft, topRight, bottomRight, bottomLeft)
            } catch (exception: Exception) {
                val (topLeft, topRight, bottomLeft, bottomRight) = getDefaultDocumentCorners(photo)
                Quad(topLeft, topRight, bottomRight, bottomLeft)
            }

            document = Document(originalPhotoPath, photo.width, photo.height, corners)

            if (letUserAdjustCrop) {
                // user is allowed to move corners to make corrections
                try {
                    // set preview image height based off of photo dimensions
                    imageView.setImagePreviewBounds(photo, screenWidth, screenHeight)

                    // display original photo, so user can adjust detected corners
                    imageView.setImage(photo)

                    // document corner points are in original image coordinates, so we need to
                    // scale and move the points to account for blank space (caused by photo and
                    // photo container having different aspect ratios)
                    val cornersInImagePreviewCoordinates = corners
                        .mapOriginalToPreviewImageCoordinates(
                            imageView.imagePreviewBounds,
                            imageView.imagePreviewBounds.height() / photo.height
                        )

                    // display cropper, and allow user to move corners
                    imageView.setCropper(cornersInImagePreviewCoordinates)
                } catch (exception: Exception) {
                    val (topLeft, topRight, bottomLeft, bottomRight) = getDefaultDocumentCorners(photo)
                    Quad(topLeft, topRight, bottomRight, bottomLeft)
                }
            } else {
                // user isn't allowed to move corners, so accept automatically detected corners
                document?.let { document ->
                    documents.add(document)
                }

                // create cropped document image, and return file path to complete document scan
                cropDocumentAndFinishIntent()
            }
        },
        onCancelGallery = {
            // user exits gallery
            // complete document scan if this is the first document since we can't go to crop view
            // until user takes at least 1 photo
            if (documents.isEmpty()) {
                onClickCancel()
            }
        }
    )

    /**
     * @property imageView container with original photo and cropper
     */
    private lateinit var imageView: ImageCropView


    /**
     * called when activity is created
     *
     * @param savedInstanceState persisted data that maintains state
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // load OpenCV
            System.loadLibrary("opencv_java4")
        } catch (exception: Exception) {
            finishIntentWithError(
                "error starting OpenCV: ${exception.message}"
            )
        }

        // Show cropper, accept crop button, add new document button, and
        // retake photo button. Since we open the camera in a few lines, the user
        // doesn't see this until they finish taking a photo
        setContentView(R.layout.activity_image_crop)
        imageView = findViewById(R.id.image_view)

        try {
            // validate maxNumDocuments option, and update default if user sets it
            var userSpecifiedMaxImages: Int? = null
            intent.extras?.get(DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS)?.let {
                if (it.toString().toIntOrNull() == null) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS} must be a positive number"
                    )
                }
                userSpecifiedMaxImages = it as Int
                maxNumDocuments = userSpecifiedMaxImages as Int
            }

            // validate letUserAdjustCrop option, and update default if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP)?.let {
                if (!arrayOf("true", "false").contains(it.toString())) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP} must true or false"
                    )
                }
                letUserAdjustCrop = it as Boolean
            }

            // if we don't want user to move corners, we can let the user only take 1 photo
            if (!letUserAdjustCrop) {
                maxNumDocuments = 1

                if (userSpecifiedMaxImages != null && userSpecifiedMaxImages != 1) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS} must be 1 when " +
                                "${DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP} is false"
                    )
                }
            }

            // validate croppedImageQuality option, and update value if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY)?.let {
                if (it !is Int || it < 0 || it > 100) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY} must be a number " +
                                "between 0 and 100"
                    )
                }
                croppedImageQuality = it
            }

            // validate imageProvider option, and update default if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_IMAGE_PROVIDER)?.let {
                if (!arrayOf(ImageProvider.CAMERA, ImageProvider.GALLERY).contains(it.toString())) {
                    throw Exception("${DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP} must be either camera or gallery")
                }
                imageProvider = it as String
            }
        } catch (exception: Exception) {
            finishIntentWithError(
                "invalid extra: ${exception.message}"
            )
            return
        }

        // set click event handlers for new document button, accept and crop document button,
        // and retake document photo button
//        val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
        val completeDocumentScanButton: ImageButton = findViewById(
            R.id.complete_document_scan_button
        )
        val retakePhotoButton: ImageButton = findViewById(R.id.retake_photo_button)
        val rotatePhoto: ImageButton = findViewById(R.id.rotate_photo)

        completeDocumentScanButton.onClick { onClickDone() }
        retakePhotoButton.onClick { onClickRetake() }
        rotatePhoto.onClick { onRotatePhoto() }


        // open camera or gallery, so user can snap or choose document photo
        if (imageProvider == ImageProvider.CAMERA) {
            try {
                openCamera()
            } catch (exception: Exception) {
                finishIntentWithError("error opening camera: ${exception.message}")
            }
        } else if (imageProvider == ImageProvider.GALLERY) {
            try {
                openGallery()
            } catch (exception: Exception) {
                finishIntentWithError("error opening gallery: ${exception.message}")
            }
        }
    }

    /**
     * Pass in a photo of a document, and get back 4 corner points (top left, top right, bottom
     * right, bottom left). This tries to detect document corners, but falls back to photo corners
     * with slight margin in case we can't detect document corners.
     *
     * @param photo the original photo with a rectangular document
     * @return a List of 4 OpenCV points (document corners)
     */
    private fun getDocumentCorners(photo: Bitmap): List<Point> {
        val cornerPoints: List<Point>? = DocumentDetector().findDocumentCorners(photo)


        // if cornerPoints is null then default the corners to the photo bounds with a margin
        return cornerPoints ?: listOf(
            Point(0.0, 0.0).move(
                cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point( photo.width.toDouble(), 0.0).move(
                -cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point(0.0, photo.height.toDouble()).move(
                cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            ),
            Point(photo.width.toDouble(), photo.height.toDouble()).move(
                -cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            )
        )
    }

    private fun getDefaultDocumentCorners(photo: Bitmap): List<Point> {
        return listOf(
            Point(0.0, 0.0).move(
                cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point( photo.width.toDouble(), 0.0).move(
                -cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point(0.0, photo.height.toDouble()).move(
                cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            ),
            Point(photo.width.toDouble(), photo.height.toDouble()).move(
                -cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            )
        )
    }

    private fun getWholeDocumentCorners(photo: Bitmap): List<Point> {

        // if cornerPoints is null then default the corners to the photo bounds with a margin
        return  listOf(
            Point(0.0, 0.0),
            Point( photo.width.toDouble(), 0.0),
            Point(0.0, photo.height.toDouble()),
            Point(photo.width.toDouble(), photo.height.toDouble())
        )
    }

    /**
     * Set document to null since we're capturing a new document, and open the camera. If the
     * user captures a photo successfully document gets updated.
     */
    private fun openCamera() {
        document = null
        cameraUtil.openCamera(documents.size)
    }

    /**
     * Set document to null since we're choosing a new document, and open the camera. If the
     * user chooses a photo successfully document gets updated.
     */
    private fun openGallery() {
        document = null
        galleryUtil.openGallery(documents.size)
    }

    /**
     * Once user accepts by pressing check button, or by pressing add new document button, add
     * original photo path and 4 document corners to documents list. If user isn't allowed to
     * adjust corners, call this automatically.
     */
    private fun addSelectedCornersAndOriginalPhotoPathToDocuments() {
        // only add document it's not null (the current document photo capture, and corner
        // detection are successful)
        document?.let { document ->
//             convert corners from image preview coordinates to original photo coordinates
//             (original image is probably bigger than the preview image)
            val cornersInOriginalImageCoordinates = imageView.corners
                .mapPreviewToOriginalImageCoordinates(
                    imageView.imagePreviewBounds,
                    imageView.imagePreviewBounds.height() / photo.height
                )
            document.corners = cornersInOriginalImageCoordinates
            documents.add(document)
        }
    }

    /**
     * This gets called when a user presses the new document button. Store current photo path
     * with document corners. Then open the camera, so user can take a photo of the next
     * page or document
     */
    private fun onClickNew() {
        addSelectedCornersAndOriginalPhotoPathToDocuments()
        if (imageProvider == ImageProvider.CAMERA) {
            openCamera()
        } else if (imageProvider == ImageProvider.GALLERY) {
            openGallery()
        }
    }


    fun rotateBitmap(sourceBitmap: Bitmap, angleDegrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angleDegrees)
        return Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
    }

    private fun onRotatePhoto() {
        val sourceImage:Bitmap = imageView.drawable.toBitmap();
        val rotatedBitmap = rotateBitmap(sourceImage, 90f) // Rotate by 90 degrees
        val outputStream = FileOutputStream(File(filePath))
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        outputStream.flush()
        outputStream.close()
        photo=rotatedBitmap
        imageView.setImage(photo)
        val corners = try {
            val (topLeft, topRight, bottomLeft, bottomRight) = getDefaultDocumentCorners(rotatedBitmap)
            Quad(topLeft, topRight, bottomRight, bottomLeft)
        } catch (exception: Exception) {
            val (topLeft, topRight, bottomLeft, bottomRight) = getDefaultDocumentCorners(photo)
            Quad(topLeft, topRight, bottomRight, bottomLeft)
        }
        document = Document(filePath, rotatedBitmap.width, rotatedBitmap.height, corners)
        imageView.setImagePreviewBounds(rotatedBitmap, screenWidth, screenHeight)
        imageView.setImage(rotatedBitmap)

        val cornersInImagePreviewCoordinates = corners
            .mapOriginalToPreviewImageCoordinates(
                imageView.imagePreviewBounds,
                imageView.imagePreviewBounds.height() / rotatedBitmap.height
            )

        // display cropper, and allow user to move corners
        imageView.setCropper(cornersInImagePreviewCoordinates)
    }




    /**
     * This gets called when a user presses the done button. Store current photo path with
     * document corners. Then crop document using corners, and return cropped image paths
     */
    private fun onClickDone() {
        addSelectedCornersAndOriginalPhotoPathToDocuments()
        cropDocumentAndFinishIntent()
    }

    /**
     * This gets called when a user presses the retake photo button. The user presses this in
     * case the original document photo isn't good, and they need to take it again.
     */
    private fun onClickRetake() {
        // we're going to retake the photo, so delete the one we just took
        document?.let { document -> File(document.originalPhotoFilePath).delete() }
        if (imageProvider == ImageProvider.CAMERA) {
            openCamera()
        } else if (imageProvider == ImageProvider.GALLERY) {
            openGallery()
        }
    }

    /**
     * This gets called when a user doesn't want to complete the document scan after starting.
     * For example a user can quit out of the camera before snapping a photo of the document.
     */
    private fun onClickCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * This crops original document photo, saves cropped document photo, deletes original
     * document photo, and returns cropped document photo file path. It repeats that for
     * all document photos.
     */
    private fun cropDocumentAndFinishIntent() {
        val croppedImageResults = arrayListOf<String>()
        for ((pageNumber, document) in documents.withIndex()) {
            // crop document photo by using corners
            val croppedImage: Bitmap = try {
                ImageUtil().crop(
                    document.originalPhotoFilePath,
                    document.corners
                )
            } catch (exception: Exception) {
                finishIntentWithError("unable to crop image: ${exception.message}")
                return
            }

            // delete original document photo
            File(document.originalPhotoFilePath).delete()

            // save cropped document photo
            try {
                val croppedImageFile = FileUtil().createImageFile(this, pageNumber)
                croppedImage.saveToFile(croppedImageFile, croppedImageQuality)
                croppedImageResults.add(Uri.fromFile(croppedImageFile).toString())
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to save cropped image: ${exception.message}"
                )
            }
        }

        // return array of cropped document photo file paths
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("croppedImageResults", croppedImageResults)
        )
        finish()
    }

    /**
     * This ends the document scanner activity, and returns an error message that can be
     * used to debug error
     *
     * @param errorMessage an error message
     */
    private fun finishIntentWithError(errorMessage: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("error", errorMessage)
        )
        finish()
    }
}
