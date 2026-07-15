#include "network_stream.h"
#include <ws2tcpip.h>
#include <algorithm>
#include <vector>

namespace droidfiles {
    namespace {
        bool all(SOCKET s, const char *p, int n, bool sendMode) {
            while (n) {
                int x = sendMode ? send(s, p, n, 0) : recv(s, const_cast<char *>(p), n, 0);
                if (x <= 0)return false;
                p += x;
                n -= x;
            }
            return true;
        }

        void u16(std::vector<char> &b, const std::string &s) {
            auto n = htons(static_cast<u_short>(s.size()));
            b.insert(b.end(), reinterpret_cast<char *>(&n), reinterpret_cast<char *>(&n) + 2);
            b.insert(b.end(), s.begin(), s.end());
        }

        std::uint64_t net64(std::uint64_t v) { return (static_cast<std::uint64_t>(htonl(static_cast<u_long>(v))) << 32) | htonl(static_cast<u_long>(v >> 32)); }
    }

    HRESULT NetworkStream::QueryInterface(REFIID id, void **out) {
        if (!out)return E_POINTER;
        *out = nullptr;
        if (id == IID_IUnknown || id == IID_ISequentialStream || id == IID_IStream)*out = this; else return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }

    HRESULT NetworkStream::Read(void *out, ULONG requested, ULONG *readOut) {
        if (readOut)*readOut = 0;
        if (!out)return STG_E_INVALIDPOINTER;
        if (offset_ >= size_ || !requested)return S_FALSE;
        requested = static_cast<ULONG>(std::min<std::uint64_t>({requested, size_ - offset_, 1024 * 1024}));
        WSADATA w{};
        if (WSAStartup(MAKEWORD(2, 2), &w))return STG_E_READFAULT;
        SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons(port_);
        InetPtonW(AF_INET, L"127.0.0.1", &a.sin_addr);
        if (connect(s, reinterpret_cast<sockaddr *>(&a), sizeof(a))) {
            closesocket(s);
            WSACleanup();
            return STG_E_READFAULT;
        }
        std::vector<char> req;
        u16(req, token_);
        u16(req, item_);
        auto o = net64(offset_);
        auto c = htonl(requested);
        req.insert(req.end(), reinterpret_cast<char *>(&o), reinterpret_cast<char *>(&o) + 8);
        req.insert(req.end(), reinterpret_cast<char *>(&c), reinterpret_cast<char *>(&c) + 4);
        std::uint32_t header[2]{};
        HRESULT hr = STG_E_READFAULT;
        if (all(s, req.data(), static_cast<int>(req.size()), true) && all(s, reinterpret_cast<char *>(header), 8, false)) {
            auto status = ntohl(header[0]);
            auto count = ntohl(header[1]);
            if (!status && count <= requested && all(s, static_cast<char *>(out), count, false)) {
                offset_ += count;
                if (readOut)*readOut = count;
                hr = count == requested ? S_OK : S_FALSE;
            }
        }
        closesocket(s);
        WSACleanup();
        return hr;
    }

    HRESULT NetworkStream::Seek(LARGE_INTEGER move, DWORD origin, ULARGE_INTEGER *pos) {
        std::int64_t base = origin == STREAM_SEEK_SET ? 0 : origin == STREAM_SEEK_CUR ? static_cast<std::int64_t>(offset_) : origin == STREAM_SEEK_END ? static_cast<std::int64_t>(size_) : -1;
        if (base < 0 || move.QuadPart < -base)return STG_E_INVALIDFUNCTION;
        auto value = base + move.QuadPart;
        if (value < 0)return STG_E_INVALIDFUNCTION;
        offset_ = value;
        if (pos)pos->QuadPart = offset_;
        return S_OK;
    }

    HRESULT NetworkStream::Stat(STATSTG *st, DWORD) {
        if (!st)return E_POINTER;
        ZeroMemory(st, sizeof(*st));
        st->type = STGTY_STREAM;
        st->cbSize.QuadPart = size_;
        st->grfMode = STGM_READ;
        return S_OK;
    }

    HRESULT NetworkStream::Clone(IStream **out) {
        if (!out)return E_POINTER;
        *out = new NetworkStream(port_, token_, item_, size_, offset_);
        return S_OK;
    }

    HRESULT NetworkStream::CopyTo(IStream *dst, ULARGE_INTEGER amount, ULARGE_INTEGER *r, ULARGE_INTEGER *w) {
        if (!dst)return E_POINTER;
        std::vector<char> b(64 * 1024);
        std::uint64_t total = 0;
        while (total < amount.QuadPart) {
            ULONG got = 0;
            auto hr = Read(b.data(), static_cast<ULONG>(std::min<std::uint64_t>(b.size(), amount.QuadPart - total)), &got);
            if (FAILED(hr))return hr;
            if (!got)break;
            ULONG put = 0;
            if (FAILED(dst->Write(b.data(), got, &put)) || put != got)return STG_E_WRITEFAULT;
            total += got;
        }
        if (r)r->QuadPart = total;
        if (w)w->QuadPart = total;
        return S_OK;
    }
}
