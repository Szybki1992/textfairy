/*
 * Copyright (C) 2012,2013 Renard Wellnitz.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.tesseract.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.googlecode.leptonica.android.Boxa
import com.googlecode.leptonica.android.Pix
import com.googlecode.leptonica.android.Pixa
import com.googlecode.leptonica.android.WriteFile
import com.googlecode.tesseract.android.TessBaseAPI.PageSegMode
import com.renard.ocr.R
import com.renard.ocr.TextFairyApplication
import com.renard.ocr.analytics.Analytics
import com.renard.ocr.analytics.CrashLogger
import com.renard.ocr.documents.creation.crop.CropImageScaler
import com.renard.ocr.main_menu.language.OcrLanguage
import com.renard.ocr.main_menu.language.OcrLanguageDataStore.deleteLanguage
import com.renard.ocr.util.AppStorage
import com.renard.ocr.util.MemoryInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class OcrProgress {
    data class Message(@StringRes val message: Int) : OcrProgress()
    data class LayoutElements(val columnBounds: List<RectF>, val imageBounds: List<RectF>, val columns: Pixa, val images: Pixa) : OcrProgress()
    data class Progress(val percent: Int, val wordBounds: RectF, val pageBounds: RectF) : OcrProgress()
    data class Result(val nativePix: Long, val utf8Text: String, val hocrText: String, val accuracy: Int) : OcrProgress()
    data class Error(@StringRes val message: Int) : OcrProgress()
}

data class PreviewPicture(val bitmap: Bitmap)

class OCR(val pix: Pix, application: TextFairyApplication) : AndroidViewModel(application) {

    private val mAnalytics: Analytics = application.analytics
    private val mCrashLogger: CrashLogger = application.crashLogger
    private val mNativeBinding: NativeBinding = NativeBinding()
    private val mStopped = AtomicBoolean(false)
    private var mCompleted = AtomicBoolean(false)
    private val mExecutorService = Executors.newSingleThreadExecutor()
    private val ocrProgress: MutableLiveData<OcrProgress> = MutableLiveData()
    private val previewPictures: MutableLiveData<PreviewPicture> = MutableLiveData()

    private var mPreviewWith: Int = 0
    private var mPreviewHeight: Int = 0
    private var mPreviewHeightUnScaled: Int = 0
    private var mPreviewWidthUnScaled: Int = 0
    private var mOriginalWidth: Int = pix.width
    private var mOriginalHeight: Int = pix.height


    private val mTess: TessBaseAPI = TessBaseAPI { progressValues ->

//    private val mTess: TessBaseAPI = TessBaseAPI(OcrProgressListener { percent, left, right, top, bottom, left2, right2, top2, bottom2 ->

        val availableMegs = MemoryInfo.getFreeMemory(getApplication())
        mCrashLogger.logMessage("available ram = $availableMegs, percent done = ${progressValues.percent}")
        mCrashLogger.setLong("ocr progress", progressValues.percent.toLong())

        // scale the word bounding rectangle to the preview image space
        val xScale = 1.0f * mPreviewWith / mOriginalWidth
        val yScale = 1.0f * mPreviewHeight / mOriginalHeight
        val wordBounds = progressValues.currentWordRect.scale(xScale, yScale)
        val ocrBounds = progressValues.currentRect.scale(xScale, yScale)

        ocrProgress.postValue(OcrProgress.Progress(progressValues.percent, wordBounds, ocrBounds))
    }

    init {
        mNativeBinding.setProgressCallBack(object : NativeBinding.ProgressCallBack {
            @WorkerThread
            override fun onProgressImage(nativePix: Long) {
                sendPreview(nativePix)
            }

            @WorkerThread
            override fun onProgressText(@StringRes message: Int) {
                ocrProgress.postValue(OcrProgress.Message(message))
            }

            @WorkerThread
            override fun onLayoutAnalysed(nativePixaText: Long, nativePixaImages: Long) {
                val xScale = 1.0f * mPreviewWith / mOriginalWidth
                val yScale = 1.0f * mPreviewHeight / mOriginalHeight
                val images = Pixa(nativePixaImages, 0, 0)
                val imageBounds = images.boxRects.map {
                    it.scale(xScale, yScale)
                }
                val columns = Pixa(nativePixaText, 0, 0)
                val columnBounds = columns.boxRects.map {
                    it.scale(xScale, yScale)
                }

                ocrProgress.postValue(OcrProgress.LayoutElements(columnBounds, imageBounds, columns, images))
            }
        })
    }

    private fun Rect.scale(xScale: Float, yScale: Float): RectF =
            RectF(
                    left * xScale,
                    top * yScale,
                    right * xScale,
                    bottom * yScale
            )

    private fun sendPreview(nativePix: Long) {
        val preview = Pix(nativePix)
        val scale = CropImageScaler().scale(preview, mPreviewWidthUnScaled, mPreviewHeightUnScaled)
        val previewBitmap = WriteFile.writeBitmap(scale.pix)
        if (previewBitmap != null) {
            scale.pix.recycle()
            mPreviewHeight = previewBitmap.height
            mPreviewWith = previewBitmap.width
            previewPictures.postValue(PreviewPicture(previewBitmap))
        }
    }

    fun getOcrProgress(): LiveData<OcrProgress> {
        return ocrProgress
    }

    fun getPreviewPictures(): LiveData<PreviewPicture> {
        return previewPictures
    }

    override fun onCleared() {
        super.onCleared()
        mCrashLogger.logMessage("OCR#onCleared")
        mStopped.set(true)
        mTess.stop()
        if (!mCompleted.get()) {
            mCrashLogger.logMessage("ocr cancelled")
            mAnalytics.sendOcrCancelled()
        }
    }

    /**
     * native code takes care of the Pix, do not use it after calling this
     * function
     **/
    fun startLayoutAnalysis(width: Int, height: Int) {
        mPreviewHeightUnScaled = height
        mPreviewWidthUnScaled = width
        mExecutorService.execute {
            mNativeBinding.analyseLayout(pix)
        }
    }

    /**
     * native code takes care of both Pixa, do not use them after calling this
     * function
     *
     * @param pixaText   must contain the binary text parts
     * @param pixaImages pixaImages must contain the image parts
     */
    fun startOCRForComplexLayout(context: Context, lang: String, pixaText: Pixa, pixaImages: Pixa, selectedTexts: IntArray, selectedImages: IntArray) {
        mExecutorService.execute(Runnable {
            mCrashLogger.logMessage("startOCRForComplexLayout")
            var pixOcr: Pix? = null
            var boxa: Boxa? = null
            try {
                logMemory(context)

                val columnData = mNativeBinding.combinePixa(pixaText.nativePixa, pixaImages.nativePixa, selectedTexts, selectedImages)
                pixaText.recycle()
                pixaImages.recycle()
                val pixOrgPointer = columnData[0]
                pixOcr = Pix(columnData[1])
                boxa = Boxa(columnData[2])

                sendPreview(pixOrgPointer)
                ocrProgress.postValue(OcrProgress.Message(R.string.progress_ocr))

                if (!initTessApi(
                                lang = lang,
                                ocrMode = TessBaseAPI.OEM_LSTM_ONLY
                        )
                ) {
                    return@Runnable
                }

                mTess.setPageSegMode(PageSegMode.PSM_SINGLE_BLOCK)
                mTess.setImage(pixOcr)

                mOriginalHeight = pixOcr.height
                mOriginalWidth = pixOcr.width

                if (mStopped.get()) {
                    return@Runnable
                }
                var accuracy = 0f
                val geometry = IntArray(4)
                val hocrText = StringBuilder()
                val htmlText = StringBuilder()
                for (i in 0 until boxa.count) {
                    if (!boxa.getGeometry(i, geometry)) {
                        continue
                    }
                    mTess.setRectangle(geometry[0], geometry[1], geometry[2], geometry[3])
                    hocrText.append(mTess.getHOCRText(0))
                    htmlText.append(mTess.htmlText)
                    accuracy += mTess.meanConfidence().toFloat()
                    if (mStopped.get()) {
                        return@Runnable
                    }
                }
                val totalAccuracy = Math.round(accuracy / boxa.count)
                ocrProgress.postValue(OcrProgress.Result(pixOrgPointer, htmlText.toString(), hocrText.toString(), totalAccuracy))
            } finally {
                mNativeBinding.destroy()
                pix.recycle()
                pixOcr?.recycle()
                boxa?.recycle()
                mTess.end()
                mCompleted.set(true)
                mCrashLogger.logMessage("startOCRForComplexLayout finished")
            }
        })
    }


    /**
     * native code takes care of the Pix, do not use it after calling this
     * function
     *
     * @param context used to access the file system
     * @param pix    source pix to do ocr on
     */
    fun startOCRForSimpleLayout(context: Context, lang: String, width: Int, height: Int) {
        mPreviewHeightUnScaled = height
        mPreviewWidthUnScaled = width

        mExecutorService.execute(Runnable {
            mCrashLogger.logMessage("startOCRForSimpleLayout")
            try {
                logMemory(context)
                sendPreview(pix.nativePix)

                val nativeTextPix = mNativeBinding.convertBookPage(pix)

                sendPreview(nativeTextPix)

                val pixText = Pix(nativeTextPix)
                ocrProgress.postValue(OcrProgress.Message(R.string.progress_ocr))
                if (mStopped.get()) {
                    return@Runnable
                }

                if (!initTessApi(lang, TessBaseAPI.OEM_LSTM_ONLY)) {
                    return@Runnable
                }

                mTess.pageSegMode = PageSegMode.PSM_AUTO
                mTess.setImage(pixText)
                var hocrText = mTess.getHOCRText(0)
                var accuracy = mTess.meanConfidence()
                val utf8Text = mTess.utF8Text

                if (!mStopped.get() && utf8Text.isEmpty()) {
                    mCrashLogger.logMessage("No words found. Looking for sparse text.")
                    mTess.setPageSegMode(PageSegMode.PSM_SPARSE_TEXT)
                    mTess.setImage(pixText)
                    hocrText = mTess.getHOCRText(0)
                    accuracy = mTess.meanConfidence()
                }

                if (mStopped.get()) {
                    return@Runnable
                }
                val htmlText = mTess.htmlText
                if (accuracy == 95) {
                    accuracy = 0
                }
                ocrProgress.postValue(OcrProgress.Result(nativeTextPix, htmlText.toString(), hocrText.toString(), accuracy))
            } finally {
                mNativeBinding.destroy()
                pix.recycle()
                mTess.end()
                mCompleted.set(true)
                mCrashLogger.logMessage("startOCRForSimpleLayout finished")
            }
        })

    }

    private fun initTessApi(lang: String, @TessBaseAPI.OcrEngineMode ocrMode: Int): Boolean {
        val tessDir = AppStorage.getTrainingDataDir(getApplication())?.path?:return false
        logTessParams(lang, ocrMode)
        val result = mTess.init(tessDir, lang, ocrMode)
        if (!result) {
            mCrashLogger.logMessage("init failed. deleting $lang")
            deleteLanguage(lang, getApplication())
            OcrLanguage(lang).installLanguage(getApplication())
            ocrProgress.postValue(OcrProgress.Error(R.string.error_tess_init))
            return false
        }
        mCrashLogger.logMessage("init succeeded")
        mTess.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, "ﬀﬁﬂﬃﬄﬅﬆ")
        return true
    }

    private fun name(@TessBaseAPI.OcrEngineMode i: Int): String =
            when (i) {
                TessBaseAPI.OEM_TESSERACT_ONLY -> "OEM_TESSERACT_ONLY"
                TessBaseAPI.OEM_LSTM_ONLY -> "OEM_LSTM_ONLY"
                TessBaseAPI.OEM_DEFAULT -> "OEM_DEFAULT"
                else -> "undefined"
            }

    private fun logTessParams(lang: String, @TessBaseAPI.OcrEngineMode ocrMode: Int) {
        with(mCrashLogger) {
            setString(
                    tag = "page seg mode",
                    value = name(ocrMode)
            )
            setString("ocr language", lang)
        }
    }

    private fun logMemory(context: Context) {
        val freeMemory = MemoryInfo.getFreeMemory(context)
        mCrashLogger.setLong("Memory", freeMemory)
    }


    companion object {
        const val FILE_NAME = "last_scan"

        fun savePixToCacheDir(context: Context, pix: Pix) {
            val dir = File(context.cacheDir, context.getString(R.string.config_share_file_dir))
            SavePixTask(pix, dir).execute()
        }

        fun getLastOriginalImageFromCache(context: Context): File {
            val dir = File(context.cacheDir, context.getString(R.string.config_share_file_dir))
            return File(dir, "$FILE_NAME.png")

        }
    }


}
