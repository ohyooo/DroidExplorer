#pragma once

#include <windows.h>
#include <shlobj.h>
#include <cstdint>
#include <string>
#include <vector>

namespace droidfiles {
    struct VirtualItem {
        std::wstring relativePath;
        bool directory;
        std::uint64_t size;
        FILETIME modified;
        std::string id{};
    };

    std::vector <std::byte> buildFileGroupDescriptor(const std::vector <VirtualItem> &items);
}
