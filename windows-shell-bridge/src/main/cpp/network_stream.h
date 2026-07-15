#pragma once

#include <winsock2.h>
#include <objidl.h>
#include <atomic>
#include <string>
#include <cstdint>

namespace droidfiles {
    class NetworkStream final : public IStream {
        std::atomic <ULONG> refs_{1};
        std::uint16_t port_;
        std::string token_, item_;
        std::uint64_t size_, offset_;
    public:
        NetworkStream(std::uint16_t port, std::string token, std::string item, std::uint64_t size, std::uint64_t offset = 0) : port_(port), token_(std::move(token)), item_(std::move(item)), size_(size), offset_(offset) {}

        HRESULT STDMETHODCALLTYPE
        QueryInterface(REFIID,
        void**)
        override;
        ULONG STDMETHODCALLTYPE

        AddRef() override { return ++refs_; }

        ULONG STDMETHODCALLTYPE

        Release() override {
            auto n = --refs_;
            if (!n)delete this;
            return n;
        }

        HRESULT STDMETHODCALLTYPE

        Read(void *, ULONG, ULONG *) override;

        HRESULT STDMETHODCALLTYPE

        Write(const void *, ULONG, ULONG *) override { return STG_E_ACCESSDENIED; }

        HRESULT STDMETHODCALLTYPE
        Seek(LARGE_INTEGER, DWORD, ULARGE_INTEGER
        *)
        override;
        HRESULT STDMETHODCALLTYPE
        SetSize(ULARGE_INTEGER)
        override{ return STG_E_ACCESSDENIED; }
        HRESULT STDMETHODCALLTYPE
        CopyTo(IStream
        *,ULARGE_INTEGER,ULARGE_INTEGER*,ULARGE_INTEGER*)
        override;
        HRESULT STDMETHODCALLTYPE
        Commit(DWORD)
        override{ return S_OK; }
        HRESULT STDMETHODCALLTYPE

        Revert() override { return E_NOTIMPL; }

        HRESULT STDMETHODCALLTYPE
        LockRegion(ULARGE_INTEGER, ULARGE_INTEGER, DWORD
        )override{ return STG_E_INVALIDFUNCTION; }
        HRESULT STDMETHODCALLTYPE
        UnlockRegion(ULARGE_INTEGER, ULARGE_INTEGER, DWORD
        )override{ return STG_E_INVALIDFUNCTION; }
        HRESULT STDMETHODCALLTYPE
        Stat(STATSTG
        *,DWORD)
        override;
        HRESULT STDMETHODCALLTYPE
        Clone(IStream
        **)
        override;
    };
}
