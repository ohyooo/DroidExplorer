#include <jni.h>
#include <ole2.h>
#include <shellapi.h>
#include "virtual_data_object.h"
#include "network_stream.h"
#include <vector>
#include <string>

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
