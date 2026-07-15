#include "descriptor.h"
#include "virtual_data_object.h"
#include <cassert>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <shlwapi.h>
#include <stdexcept>

namespace {
    void verifyShellFolderExtraction() {
        namespace fs = std::filesystem;
        const auto root = fs::temp_directory_path() /
                          (L"droidfiles-shell-drop-" + std::to_wstring(GetCurrentProcessId()) + L"-" +
                           std::to_wstring(GetTickCount64()));
        fs::create_directories(root);

        PIDLIST_ABSOLUTE absolute = nullptr;
        if (FAILED(SHParseDisplayName(root.c_str(), nullptr, &absolute, 0, nullptr)))
            throw std::runtime_error("SHParseDisplayName failed");
        IShellFolder *parent = nullptr;
        PCUITEMID_CHILD child = nullptr;
        if (FAILED(SHBindToParent(absolute, IID_PPV_ARGS(&parent), &child)))
            throw std::runtime_error("SHBindToParent failed");
        IDropTarget *target = nullptr;
        if (FAILED(parent->GetUIObjectOf(nullptr, 1, &child, IID_IDropTarget, nullptr,
                                         reinterpret_cast<void **>(&target))))
            throw std::runtime_error("GetUIObjectOf(IDropTarget) failed");

        FILETIME time{};
        const std::vector <BYTE> unicodeContent = {0xE4, 0xBD, 0xA0, 0xE5, 0xA5, 0xBD};
        auto *object = new droidfiles::VirtualDataObject(
                {{L"目录",           true,  0,                     time},
                 {L"目录\\内容.txt", false, unicodeContent.size(), time},
                 {L"空文件.bin",     false, 0,                     time}},
                [unicodeContent](std::size_t index, IStream **out) -> HRESULT {
                    if (index == 1) {
                        *out = SHCreateMemStream(unicodeContent.data(), static_cast<UINT>(unicodeContent.size()));
                    } else if (index == 2) {
                        *out = SHCreateMemStream(nullptr, 0);
                    } else {
                        return DV_E_LINDEX;
                    }
                    return *out ? S_OK : E_OUTOFMEMORY;
                });
        object->SetAsyncMode(FALSE);
        DWORD effect = DROPEFFECT_COPY;
        POINTL point{};
        if (FAILED(target->DragEnter(object, MK_LBUTTON, point, &effect)))
            throw std::runtime_error("IDropTarget::DragEnter failed");
        effect = DROPEFFECT_COPY;
        if (FAILED(target->Drop(object, 0, point, &effect)))
            throw std::runtime_error("IDropTarget::Drop failed");
        if ((effect & DROPEFFECT_COPY) == 0) throw std::runtime_error("Shell rejected COPY effect");

        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        while ((!fs::exists(root / L"目录" / L"内容.txt") || !fs::exists(root / L"空文件.bin")) &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        std::ifstream input(root / L"目录" / L"内容.txt", std::ios::binary);
        const std::vector <BYTE> actual((std::istreambuf_iterator<char>(input)), {});
        if (actual != unicodeContent) throw std::runtime_error("Shell did not extract nested Unicode content");
        if (!fs::exists(root / L"空文件.bin") || fs::file_size(root / L"空文件.bin") != 0)
            throw std::runtime_error("Shell did not extract empty file");

        target->Release();
        parent->Release();
        CoTaskMemFree(absolute);
        object->Release();
        std::error_code cleanupError;
        for (int attempt = 0; attempt < 20; ++attempt) {
            fs::remove_all(root, cleanupError);
            if (!fs::exists(root)) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
}

int wmain() {
    const auto ole = OleInitialize(nullptr);
    assert(SUCCEEDED(ole));
    FILETIME time{123, 456};
    auto bytes = droidfiles::buildFileGroupDescriptor({{L"目录",             true,  0,              time},
                                                       {L"目录\\大文件.bin", false, 0x100000005ULL, time},
                                                       {L"空文件",           false, 0,              time}});
    auto *group = reinterpret_cast<const FILEGROUPDESCRIPTORW *>(bytes.data());
    assert(group->cItems == 3);
    assert(group->fgd[0].dwFileAttributes == FILE_ATTRIBUTE_DIRECTORY);
    assert(group->fgd[1].nFileSizeHigh == 1 && group->fgd[1].nFileSizeLow == 5);
    assert(std::wstring(group->fgd[1].cFileName) == L"目录\\大文件.bin");
    assert((group->fgd[2].dwFlags & FD_FILESIZE) != 0 && group->fgd[2].nFileSizeLow == 0);
    assert(!droidfiles::buildFileGroupDescriptor({{L"valid..name.txt", false, 1, time}}).empty());
    bool traversalRejected = false;
    try { droidfiles::buildFileGroupDescriptor({{L"folder\\..\\escape.txt", false, 1, time}}); } catch (const std::invalid_argument &) { traversalRejected = true; }
    assert(traversalRejected);
    auto *object = new droidfiles::VirtualDataObject({{L"folder",   true,  0, time},
                                                      {L"file.bin", false, 5, time}}, [](std::size_t index, IStream **out) {
        const BYTE bytes[] = {1, 2, 3, 4, 5};
        *out = SHCreateMemStream(bytes, sizeof(bytes));
        return *out ? S_OK : E_OUTOFMEMORY;
    });
    FORMATETC descriptor{static_cast<CLIPFORMAT>(RegisterClipboardFormatW(CFSTR_FILEDESCRIPTORW)), nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL};
    STGMEDIUM medium{};
    assert(object->GetData(&descriptor, &medium) == S_OK);
    ReleaseStgMedium(&medium);
    FORMATETC directoryContents{static_cast<CLIPFORMAT>(RegisterClipboardFormatW(CFSTR_FILECONTENTS)), nullptr, DVASPECT_CONTENT, 0, TYMED_ISTREAM};
    assert(object->QueryGetData(&directoryContents) == DV_E_FORMATETC);
    directoryContents.lindex = 1;
    assert(object->GetData(&directoryContents, &medium) == S_OK);
    BYTE read[5]{};
    ULONG count = 0;
    assert(medium.pstm->Read(read, 5, &count) == S_OK && count == 5 && read[4] == 5);
    ReleaseStgMedium(&medium);
    BOOL async = FALSE;
    assert(object->GetAsyncMode(&async) == S_OK && async);
    assert(object->SetAsyncMode(FALSE) == S_OK && object->GetAsyncMode(&async) == S_OK && !async);
    BOOL active = FALSE;
    assert(object->StartOperation(nullptr) == S_OK && object->InOperation(&active) == S_OK && active);
    assert(object->EndOperation(S_OK, nullptr, DROPEFFECT_COPY) == S_OK && object->InOperation(&active) == S_OK && !active);
    object->Release();
    try { verifyShellFolderExtraction(); }
    catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
    OleUninitialize();
    std::cout << "descriptor and Windows Shell extraction tests passed\n";
    return 0;
}
