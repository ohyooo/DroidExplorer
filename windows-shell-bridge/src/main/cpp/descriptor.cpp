#include "descriptor.h"
#include <stdexcept>
#include <cstring>

namespace droidfiles {
    namespace {
        bool validRelativePath(const std::wstring &path) {
            if (path.empty() || path.size() >= MAX_PATH || path.front() == L'\\' || path.front() == L'/' || path.find(L'/') != std::wstring::npos) return false;
            std::size_t start = 0;
            while (start < path.size()) {
                const auto end = path.find(L'\\', start);
                const auto part = path.substr(start, end == std::wstring::npos ? path.size() - start : end - start);
                if (part.empty() || part == L"." || part == L".." || part.find_first_of(L"<>:\"|?*") != std::wstring::npos) return false;
                if (end == std::wstring::npos) return true;
                start = end + 1;
            }
            return false;
        }
    }

    std::vector <std::byte> buildFileGroupDescriptor(const std::vector <VirtualItem> &items) {
        if (items.empty()) throw std::invalid_argument("manifest must contain an item");
        const auto bytes = sizeof(UINT) + items.size() * sizeof(FILEDESCRIPTORW);
        std::vector <std::byte> storage(bytes);
        auto *group = reinterpret_cast<FILEGROUPDESCRIPTORW *>(storage.data());
        group->cItems = static_cast<UINT>(items.size());
        for (std::size_t i = 0; i < items.size(); ++i) {
            const auto &item = items[i];
            if (!validRelativePath(item.relativePath)) throw std::invalid_argument("invalid virtual path");
            auto &out = group->fgd[i];
            out.dwFlags = FD_ATTRIBUTES | FD_WRITESTIME | FD_PROGRESSUI;
            out.dwFileAttributes = item.directory ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
            out.ftLastWriteTime = item.modified;
            if (!item.directory) {
                out.dwFlags |= FD_FILESIZE;
                out.nFileSizeHigh = static_cast<DWORD>(item.size >> 32);
                out.nFileSizeLow = static_cast<DWORD>(item.size);
            }
            memcpy(out.cFileName, item.relativePath.c_str(), (item.relativePath.size() + 1) * sizeof(wchar_t));
        }
        return storage;
    }
}
