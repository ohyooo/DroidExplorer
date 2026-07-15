#include "virtual_data_object.h"
#include <shlwapi.h>

namespace droidfiles {
    VirtualDataObject::VirtualDataObject(std::vector <VirtualItem> i, StreamFactory s) : items_(std::move(i)), streams_(std::move(s)) {
        descriptor_ = RegisterClipboardFormatW(CFSTR_FILEDESCRIPTORW);
        contents_ = RegisterClipboardFormatW(CFSTR_FILECONTENTS);
    }

    HRESULT VirtualDataObject::QueryInterface(REFIID id, void **out) {
        if (!out)return E_POINTER;
        *out = nullptr;
        if (id == IID_IUnknown || id == IID_IDataObject)*out = static_cast<IDataObject *>(this); else if (id == IID_IDataObjectAsyncCapability)*out = static_cast<IDataObjectAsyncCapability *>(this); else return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }

    ULONG VirtualDataObject::AddRef() { return ++refs_; }

    ULONG VirtualDataObject::Release() {
        auto n = --refs_;
        if (!n)delete this;
        return n;
    }

    HRESULT VirtualDataObject::QueryGetData(FORMATETC *f) {
        if (!f)return E_POINTER;
        if (f->cfFormat == descriptor_ && (f->tymed & TYMED_HGLOBAL))return S_OK;
        if (f->cfFormat == contents_ && (f->tymed & TYMED_ISTREAM) && f->lindex >= 0 && static_cast<size_t>(f->lindex) < items_.size() && !items_[f->lindex].directory)return S_OK;
        return DV_E_FORMATETC;
    }

    HRESULT VirtualDataObject::GetData(FORMATETC *f, STGMEDIUM *m) {
        if (!m)return E_POINTER;
        ZeroMemory(m, sizeof(*m));
        auto q = QueryGetData(f);
        if (FAILED(q))return q;
        if (f->cfFormat == descriptor_) {
            auto bytes = buildFileGroupDescriptor(items_);
            auto h = GlobalAlloc(GMEM_MOVEABLE, bytes.size());
            if (!h)return E_OUTOFMEMORY;
            auto p = GlobalLock(h);
            memcpy(p, bytes.data(), bytes.size());
            GlobalUnlock(h);
            m->tymed = TYMED_HGLOBAL;
            m->hGlobal = h;
            return S_OK;
        }
        IStream *stream = nullptr;
        auto hr = streams_(f->lindex, &stream);
        if (FAILED(hr))return hr;
        m->tymed = TYMED_ISTREAM;
        m->pstm = stream;
        return S_OK;
    }

    HRESULT VirtualDataObject::EnumFormatEtc(DWORD dir, IEnumFORMATETC **out) {
        if (dir != DATADIR_GET)return E_NOTIMPL;
        FORMATETC formats[2] = {{descriptor_, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL},
                                {contents_,   nullptr, DVASPECT_CONTENT, -1, TYMED_ISTREAM}};
        return SHCreateStdEnumFmtEtc(2, formats, out);
    }

    HRESULT CopyDropSource::QueryInterface(REFIID id, void **out) {
        if (!out)return E_POINTER;
        *out = nullptr;
        if (id == IID_IUnknown || id == IID_IDropSource)*out = this; else return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }
}
