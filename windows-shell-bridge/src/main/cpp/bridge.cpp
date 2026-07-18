#include <jni.h>
#include <ole2.h>
#include <shellapi.h>
#include <gdiplus.h>
#include <shlwapi.h>
#include "virtual_data_object.h"
#include "network_stream.h"
#include <vector>
#include <string>
#include <memory>
#include <climits>
#include <cstring>

namespace {
    std::wstring wide(JNIEnv *e, jstring s) {
        const jchar *p = e->GetStringChars(s, nullptr);
        auto n = e->GetStringLength(s);
        std::wstring value(reinterpret_cast<const wchar_t *>(p), n);
        e->ReleaseStringChars(s, p);
        return value;
    }

    std::string utf8(JNIEnv *e, jstring s) {
        const char *p = e->GetStringUTFChars(s, nullptr);
        std::string value(p);
        e->ReleaseStringUTFChars(s, p);
        return value;
    }

    FILETIME filetime(jlong millis) {
        ULARGE_INTEGER u{};
        u.QuadPart = static_cast<ULONGLONG>(millis) * 10000ULL + 116444736000000000ULL;
        return {u.LowPart, u.HighPart};
    }

    bool png_encoder(CLSID &result) {
        UINT count = 0;
        UINT bytes = 0;
        if (Gdiplus::GetImageEncodersSize(&count, &bytes) != Gdiplus::Ok || bytes == 0)return false;
        std::vector<BYTE> storage(bytes);
        auto *encoders = reinterpret_cast<Gdiplus::ImageCodecInfo *>(storage.data());
        if (Gdiplus::GetImageEncoders(count, bytes, encoders) != Gdiplus::Ok)return false;
        for (UINT i = 0; i < count; ++i) {
            if (wcscmp(encoders[i].MimeType, L"image/png") == 0) {
                result = encoders[i].Clsid;
                return true;
            }
        }
        return false;
    }

    bool icon_png(HICON icon, int size, std::vector<BYTE> &result) {
        Gdiplus::GdiplusStartupInput input;
        ULONG_PTR token = 0;
        if (Gdiplus::GdiplusStartup(&token, &input, nullptr) != Gdiplus::Ok)return false;
        bool success = false;
        {
            std::unique_ptr<Gdiplus::Bitmap> source(Gdiplus::Bitmap::FromHICON(icon));
            Gdiplus::Bitmap target(size, size, PixelFormat32bppARGB);
            Gdiplus::Graphics graphics(&target);
            graphics.Clear(Gdiplus::Color(0, 0, 0, 0));
            graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
            CLSID encoder{};
            IStream *stream = nullptr;
            if (source && source->GetLastStatus() == Gdiplus::Ok && graphics.DrawImage(source.get(), 0, 0, size, size) == Gdiplus::Ok && png_encoder(encoder) && SUCCEEDED(CreateStreamOnHGlobal(nullptr, TRUE, &stream))) {
                if (target.Save(stream, &encoder, nullptr) == Gdiplus::Ok) {
                    HGLOBAL memory = nullptr;
                    if (SUCCEEDED(GetHGlobalFromStream(stream, &memory))) {
                        const auto length = GlobalSize(memory);
                        const auto *bytes = static_cast<const BYTE *>(GlobalLock(memory));
                        if (bytes && length > 0) {
                            result.assign(bytes, bytes + length);
                            success = true;
                        }
                        if (bytes)GlobalUnlock(memory);
                    }
                }
                stream->Release();
            }
        }
        Gdiplus::GdiplusShutdown(token);
        return success;
    }
}
extern "C" JNIEXPORT jint

JNICALL Java_dev_droidfiles_windows_NativeDragBridge_beginVirtualFileDrag(JNIEnv *env, jobject, jobjectArray paths, jobjectArray ids, jlongArray sizes, jbooleanArray directories, jlongArray modified, jint port, jstring tokenValue) {
    const auto count = env->GetArrayLength(paths);
    if (count <= 0 || env->GetArrayLength(ids) != count || env->GetArrayLength(sizes) != count || env->GetArrayLength(directories) != count || env->GetArrayLength(modified) != count || port <= 0 || port > 65535)return E_INVALIDARG;
    auto sizeValues = env->GetLongArrayElements(sizes, nullptr);
    auto dirValues = env->GetBooleanArrayElements(directories, nullptr);
    auto timeValues = env->GetLongArrayElements(modified, nullptr);
    std::vector <droidfiles::VirtualItem> items;
    items.reserve(count);
    std::vector <std::string> itemIds;
    itemIds.reserve(count);
    for (jsize i = 0; i < count; i++) {
        auto p = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        auto id = static_cast<jstring>(env->GetObjectArrayElement(ids, i));
        items.push_back({wide(env, p), dirValues[i] != JNI_FALSE, static_cast<std::uint64_t>(sizeValues[i]), filetime(timeValues[i]), utf8(env, id)});
        itemIds.push_back(items.back().id);
        env->DeleteLocalRef(p);
        env->DeleteLocalRef(id);
    }
    env->ReleaseLongArrayElements(sizes, sizeValues, JNI_ABORT);
    env->ReleaseBooleanArrayElements(directories, dirValues, JNI_ABORT);
    env->ReleaseLongArrayElements(modified, timeValues, JNI_ABORT);
    auto token = utf8(env, tokenValue);
    auto hr = OleInitialize(nullptr);
    if (FAILED(hr))return hr;
    auto *object = new droidfiles::VirtualDataObject(items, [port, token, itemIds, items](std::size_t index, IStream **out) -> HRESULT {
        if (!out || index >= items.size() || items[index].directory)return DV_E_LINDEX;
        *out = new droidfiles::NetworkStream(static_cast<std::uint16_t>(port), token, itemIds[index], items[index].size);
        return S_OK;
    });
    auto *source = new droidfiles::CopyDropSource();
    DWORD effect = DROPEFFECT_NONE;
    hr = DoDragDrop(object, source, DROPEFFECT_COPY, &effect);
    source->Release();
    object->Release();
    OleUninitialize();
    return SUCCEEDED(hr) ? static_cast<jint>(effect) : static_cast<jint>(hr);
}

extern "C" JNIEXPORT jint

JNICALL Java_dev_droidfiles_windows_NativeDragBridge_shellOpen(JNIEnv *env, jobject, jstring pathValue) {
    auto path = wide(env, pathValue);
    SHELLEXECUTEINFOW info{sizeof(info)};
    info.fMask = SEE_MASK_FLAG_NO_UI;
    info.lpVerb = L"open";
    info.lpFile = path.c_str();
    info.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&info))return static_cast<jint>(GetLastError());
    return static_cast<jint>(reinterpret_cast<INT_PTR>(info.hInstApp));
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_droidfiles_windows_NativeDragBridge_shellIconPng(JNIEnv *env, jobject, jstring fileNameValue, jboolean directory, jint size) {
    if (size < 16 || size > 256)return nullptr;
    const auto fileName = wide(env, fileNameValue);
    SHFILEINFOW info{};
    const DWORD attributes = directory != JNI_FALSE ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
    const UINT flags = SHGFI_ICON | SHGFI_USEFILEATTRIBUTES | (size <= 20 ? SHGFI_SMALLICON : SHGFI_LARGEICON);
    if (SHGetFileInfoW(fileName.c_str(), attributes, &info, sizeof(info), flags) == 0 || !info.hIcon)return nullptr;
    std::vector<BYTE> png;
    const bool encoded = icon_png(info.hIcon, size, png);
    DestroyIcon(info.hIcon);
    if (!encoded || png.size() > static_cast<std::size_t>(INT_MAX))return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(png.size()));
    if (result)env->SetByteArrayRegion(result, 0, static_cast<jsize>(png.size()), reinterpret_cast<const jbyte *>(png.data()));
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_dev_droidfiles_windows_NativeDragBridge_setClipboardFiles(JNIEnv *env, jobject, jobjectArray pathValues) {
    const auto count = env->GetArrayLength(pathValues);
    if (count <= 0)return ERROR_INVALID_PARAMETER;
    std::vector<std::wstring> paths;
    paths.reserve(count);
    std::size_t characters = 1;
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(pathValues, i));
        auto path = wide(env, value);
        env->DeleteLocalRef(value);
        if (path.empty())return ERROR_INVALID_PARAMETER;
        characters += path.size() + 1;
        paths.push_back(std::move(path));
    }
    const auto bytes = sizeof(DROPFILES) + characters * sizeof(wchar_t);
    auto memory = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes);
    if (!memory)return ERROR_NOT_ENOUGH_MEMORY;
    auto *drop = static_cast<DROPFILES *>(GlobalLock(memory));
    if (!drop) {
        GlobalFree(memory);
        return ERROR_NOT_ENOUGH_MEMORY;
    }
    drop->pFiles = sizeof(DROPFILES);
    drop->fWide = TRUE;
    auto *cursor = reinterpret_cast<wchar_t *>(reinterpret_cast<BYTE *>(drop) + sizeof(DROPFILES));
    for (const auto &path : paths) {
        memcpy(cursor, path.c_str(), path.size() * sizeof(wchar_t));
        cursor += path.size() + 1;
    }
    GlobalUnlock(memory);
    bool opened = false;
    for (int attempt = 0; attempt < 5 && !opened; ++attempt) {
        opened = OpenClipboard(nullptr) != FALSE;
        if (!opened)Sleep(10);
    }
    if (!opened) {
        const auto error = GetLastError();
        GlobalFree(memory);
        return error == ERROR_SUCCESS ? ERROR_ACCESS_DENIED : static_cast<jint>(error);
    }
    if (!EmptyClipboard() || !SetClipboardData(CF_HDROP, memory)) {
        const auto error = GetLastError();
        CloseClipboard();
        GlobalFree(memory);
        return error == ERROR_SUCCESS ? ERROR_GEN_FAILURE : static_cast<jint>(error);
    }
    CloseClipboard();
    return ERROR_SUCCESS;
}

extern "C" JNIEXPORT jint JNICALL Java_dev_droidfiles_windows_NativeDragBridge_setClipboardText(JNIEnv *env, jobject, jstring textValue) {
    const auto text = wide(env, textValue);
    const auto bytes = (text.size() + 1) * sizeof(wchar_t);
    auto memory = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes);
    if (!memory)return ERROR_NOT_ENOUGH_MEMORY;
    auto *target = static_cast<wchar_t *>(GlobalLock(memory));
    if (!target) {
        GlobalFree(memory);
        return ERROR_NOT_ENOUGH_MEMORY;
    }
    memcpy(target, text.c_str(), text.size() * sizeof(wchar_t));
    GlobalUnlock(memory);
    bool opened = false;
    for (int attempt = 0; attempt < 5 && !opened; ++attempt) {
        opened = OpenClipboard(nullptr) != FALSE;
        if (!opened)Sleep(10);
    }
    if (!opened) {
        const auto error = GetLastError();
        GlobalFree(memory);
        return error == ERROR_SUCCESS ? ERROR_ACCESS_DENIED : static_cast<jint>(error);
    }
    if (!EmptyClipboard() || !SetClipboardData(CF_UNICODETEXT, memory)) {
        const auto error = GetLastError();
        CloseClipboard();
        GlobalFree(memory);
        return error == ERROR_SUCCESS ? ERROR_GEN_FAILURE : static_cast<jint>(error);
    }
    CloseClipboard();
    return ERROR_SUCCESS;
}
