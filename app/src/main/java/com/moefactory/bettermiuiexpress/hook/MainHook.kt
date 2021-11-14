package com.moefactory.bettermiuiexpress.hook

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.moefactory.bettermiuiexpress.activity.ExpressDetailsActivity
import com.moefactory.bettermiuiexpress.api.KuaiDi100Api
import com.moefactory.bettermiuiexpress.base.app.customer
import com.moefactory.bettermiuiexpress.base.app.secretKey
import com.moefactory.bettermiuiexpress.base.intercepter.KuaiDi100Interceptor
import com.moefactory.bettermiuiexpress.model.KuaiDi100Company
import com.moefactory.bettermiuiexpress.model.KuaiDi100RequestParam
import com.moefactory.bettermiuiexpress.model.MiuiExpress
import com.moefactory.bettermiuiexpress.utils.ExpressCompanyUtils
import com.moefactory.bettermiuiexpress.utils.SignUtils
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

class MainHook : IXposedHookLoadPackage {

    private val jsonParser by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .addInterceptor(KuaiDi100Interceptor())
            .build()
    }

    private val retrofit by lazy {
        Retrofit.Builder()
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .client(okHttpClient)
            .build()
    }

    private val kuaiDi100 by lazy {
        retrofit.create(KuaiDi100Api::class.java)
    }

    companion object {
        // Package name
        private const val PA_PACKAGE_NAME = "com.miui.personalassistant"

        // Fully-qualified name of ExpressIntentUtils
        private const val PA_EXPRESS_INTENT_UTILS_OLD =
            "com.miui.personalassistant.express.ExpressIntentUtils"
        private const val PA_EXPRESS_INTENT_UTILS =
            "com.miui.personalassistant.service.express.ExpressIntentUtils"

        // Fully-qualified name of ExpressEntry
        private const val PA_EXPRESS_ENTRY_OLD =
            "com.miui.personalassistant.express.bean.ExpressEntry"
        private const val PA_EXPRESS_ENTRY =
            "com.miui.personalassistant.service.express.bean.ExpressEntry"

        // Fully-qualified name of ExpressRepository
        private const val PA_EXPRESS_REPOSITORY_OLD =
            "com.miui.personalassistant.express.ExpressRepository"
        private const val PA_EXPRESS_REPOSITOIRY =
            "com.miui.personalassistant.service.express.ExpressRepository"

        // Fully-qualified name of ExpressInfo$Detail
        private const val PA_EXPRESS_INFO_DETAIL_OLD =
            "com.miui.personalassistant.express.bean.ExpressInfo\$Detail"
        private const val PA_EXPRESS_INFO_DETAIL =
            "com.miui.personalassistant.service.express.bean.ExpressInfo\$Detail"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == PA_PACKAGE_NAME) {
            hookForExpressDetails(lpparam)
            hookForExpressCardView(lpparam)
        }
    }

    private fun hookForExpressDetails(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Detect new Personal Assistant
        val isNewVersion =
            XposedHelpers.findClassIfExists(PA_EXPRESS_INTENT_UTILS, lpparam.classLoader) != null

        val expressIntentUtilsClass = XposedHelpers.findClass(
            if (isNewVersion) PA_EXPRESS_INTENT_UTILS else PA_EXPRESS_INTENT_UTILS_OLD,
            lpparam.classLoader
        )
        val expressEntryClass = XposedHelpers.findClass(
            if (isNewVersion) PA_EXPRESS_ENTRY else PA_EXPRESS_ENTRY_OLD,
            lpparam.classLoader
        )
        XposedHelpers.findAndHookMethod(
            expressIntentUtilsClass,
            "gotoExpressDetailPage",
            Context::class.java,
            expressEntryClass,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val context = param.args[0] as Context
                    val expressEntry = param.args[1]
                    val companyCode = expressEntry.javaClass.getField("companyCode")
                        .get(expressEntry) as String
                    val companyName = expressEntry.javaClass.getField("companyName")
                        .get(expressEntry) as String
                    val mailNumber =
                        expressEntry.javaClass.getField("orderNumber").get(expressEntry) as String
                    val phoneNumber =
                        expressEntry.javaClass.getField("phone").get(expressEntry) as? String
                    // Check if the details will be showed in third-party apps(taobao, cainiao, etc.)
                    val uris =
                        expressEntry.javaClass.getMethod("getUris").invoke(expressEntry) as List<*>?
                    if (!uris.isNullOrEmpty()) {
                        // Store urls for future use such as jumping to third-party apps
                        val uriList = arrayListOf<String>()
                        for (uriEntity in uris) {
                            val uriString = uriEntity!!.javaClass.getMethod("getLink")
                                .invoke(uriEntity) as String
                            uriList.add(uriString)
                        }
                        ExpressDetailsActivity.gotoDetailsActivity(
                            context,
                            MiuiExpress(companyCode, companyName, mailNumber, phoneNumber),
                            uriList
                        )
                        return null
                    } else {
                        val provider = expressEntry.javaClass.getMethod("getProvider")
                            .invoke(expressEntry) as? String
                        val isXiaomi = provider == "Miguo" || provider == "MiMall"
                        val isJingDong = companyCode == "JDKD"
                        // Details of packages from Xiaomi or JingDong will be showed in built-in app
                        if (!isXiaomi && !isJingDong) {
                            ExpressDetailsActivity.gotoDetailsActivity(
                                context,
                                MiuiExpress(companyCode, companyName, mailNumber, phoneNumber),
                                null
                            )
                            return null
                        }
                    }

                    // Other details will be processed normally
                    return XposedBridge.invokeOriginalMethod(
                        param.method,
                        param.thisObject,
                        param.args
                    )
                }
            })
    }

    private fun hookForExpressCardView(lpparam: XC_LoadPackage.LoadPackageParam) {
        val expressRepositoryClass = try {
            XposedHelpers.findClass(PA_EXPRESS_REPOSITOIRY, lpparam.classLoader)
        } catch (e: XposedHelpers.ClassNotFoundError) {
            XposedHelpers.findClass(PA_EXPRESS_REPOSITORY_OLD, lpparam.classLoader)
        }

        XposedHelpers.findAndHookMethod(
            expressRepositoryClass,
            "saveExpress",
            java.util.List::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runBlocking {
                        val expressInfoList = param.args[0] as java.util.List<*>
                        for (expressInfo in expressInfoList) {
                            // Prevent detail from disappearing
                            expressInfo.javaClass.getMethod(
                                "setClickDisappear",
                                Boolean::class.javaPrimitiveType
                            ).invoke(expressInfo, false)

                            // Get the company code
                            val mailNumber = expressInfo.javaClass.getField("orderNumber")
                                .get(expressInfo) as String
                            val companyCode = expressInfo.javaClass.getField("companyCode")
                                .get(expressInfo) as String
                            var convertedCompanyCode = ExpressCompanyUtils.convertCode(companyCode)
                            if (convertedCompanyCode == null) {
                                convertedCompanyCode = getCompanyCode(
                                    kuaiDi100.queryExpressCompany(
                                        secretKey,
                                        mailNumber
                                    )
                                )
                            }

                            // Get the details
                            val data =
                                Json.encodeToString(
                                    KuaiDi100RequestParam(
                                        convertedCompanyCode,
                                        mailNumber
                                    )
                                )
                            val response = kuaiDi100.queryPackage(
                                customer,
                                data,
                                SignUtils.sign(data, secretKey, customer)
                            )
                            val originalDetails = expressInfo.javaClass.getField("details")
                                .get(expressInfo) as? ArrayList<Any>
                            val detailClass = try {
                                XposedHelpers.findClass(PA_EXPRESS_INFO_DETAIL, lpparam.classLoader)
                            } catch (e: XposedHelpers.ClassNotFoundError) {
                                XposedHelpers.findClass(PA_EXPRESS_INFO_DETAIL_OLD, lpparam.classLoader)
                            }
                            when {
                                originalDetails == null -> {
                                    // Null list, create a new instance and put the latest detail
                                    val newDetail = detailClass.newInstance()
                                    newDetail.javaClass.getMethod(
                                        "setDesc",
                                        java.lang.String::class.java
                                    ).invoke(newDetail, response.data!![0].context)
                                    newDetail.javaClass.getMethod(
                                        "setTime",
                                        java.lang.String::class.java
                                    ).invoke(newDetail, response.data[0].formattedTime)
                                    val newDetails = ArrayList<Any>(1)
                                    newDetails.add(newDetail)
                                    expressInfo.javaClass
                                        .getMethod("setDetails", java.util.ArrayList::class.java)
                                        .invoke(expressInfo, newDetails)
                                }
                                originalDetails.isEmpty() -> {
                                    // Empty list, put the latest detail
                                    val newDetail = detailClass.newInstance()
                                    newDetail.javaClass.getMethod(
                                        "setDesc",
                                        java.lang.String::class.java
                                    ).invoke(newDetail, response.data!![0].context)
                                    newDetail.javaClass.getMethod(
                                        "setTime",
                                        java.lang.String::class.java
                                    ).invoke(newDetail, response.data[0].formattedTime)
                                    originalDetails.add(newDetail)
                                }
                                else -> {
                                    // Normally, the original details contains one item
                                    val originalDetail = (expressInfo.javaClass.getField("details")
                                        .get(expressInfo) as List<*>)[0]
                                    originalDetail?.javaClass?.getMethod(
                                        "setDesc",
                                        java.lang.String::class.java
                                    )?.invoke(originalDetail, response.data!![0].context)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    private fun getCompanyCode(response: String): String {
        when {
            // Normal
            response.startsWith("[") -> {
                val result = Json.decodeFromString<List<KuaiDi100Company>>(response)
                return result[0].companyCode
            }
            // Error
            response.startsWith("{") -> {
                val message =
                    Json.parseToJsonElement(response).jsonObject["message"]?.jsonPrimitive?.content
                throw Exception(message)
            }
            // Exception
            else -> throw Exception("Unexpected response: $response")
        }
    }

}