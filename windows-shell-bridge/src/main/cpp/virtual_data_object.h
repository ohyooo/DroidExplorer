#pragma once

#include "descriptor.h"
#include <objidl.h>
#include <functional>
#include <atomic>

namespace droidfiles {
    using StreamFactory = std::function<HRESULT(std::size_t, IStream * *)>;

    class VirtualDataObject final : public IDataObject, public IDataObjectAsyncCapability {
        std::atomic <ULONG> refs_{1};
        std::vector <VirtualItem> items_;
        StreamFactory streams_;
        std::atomic_bool async_{true};
        std::atomic_bool operation_{false};
        CLIPFORMAT descriptor_;
        CLIPFORMAT contents_;
    public:
        VirtualDataObject(std::vector <VirtualItem> items, StreamFactory streams);

        HRESULT STDMETHODCALLTYPE
        QueryInterface(REFIID,
        void**)
        override;
        ULONG STDMETHODCALLTYPE

        AddRef() override;

        ULONG STDMETHODCALLTYPE

        Release() override;

        HRESULT STDMETHODCALLTYPE
        GetData(FORMATETC
        *,STGMEDIUM*)
        override;
        HRESULT STDMETHODCALLTYPE
        GetDataHere(FORMATETC
        *,STGMEDIUM*)override{ return E_NOTIMPL; }
        HRESULT STDMETHODCALLTYPE
        QueryGetData(FORMATETC
        *)
        override;
        HRESULT STDMETHODCALLTYPE
        GetCanonicalFormatEtc(FORMATETC
        *,
        FORMATETC *out
        )override{
            out->ptd = nullptr;
            return E_NOTIMPL;
        }
        HRESULT STDMETHODCALLTYPE
        SetData(FORMATETC
        *,STGMEDIUM*,BOOL)override{ return E_NOTIMPL; }
        HRESULT STDMETHODCALLTYPE
        EnumFormatEtc(DWORD, IEnumFORMATETC
        **)
        override;
        HRESULT STDMETHODCALLTYPE
        DAdvise(FORMATETC
        *,DWORD,IAdviseSink*,DWORD*)override{ return OLE_E_ADVISENOTSUPPORTED; }
        HRESULT STDMETHODCALLTYPE
        DUnadvise(DWORD)
        override{ return OLE_E_ADVISENOTSUPPORTED; }
        HRESULT STDMETHODCALLTYPE
        EnumDAdvise(IEnumSTATDATA
        **)override{ return OLE_E_ADVISENOTSUPPORTED; }
        HRESULT STDMETHODCALLTYPE
        SetAsyncMode(BOOL
        value)override{
            async_ = value != FALSE;
            return S_OK;
        }
        HRESULT STDMETHODCALLTYPE
        GetAsyncMode(BOOL
        * value)override{
            if (!value)return E_POINTER;
            *value = async_;
            return S_OK;
        }
        HRESULT STDMETHODCALLTYPE
        StartOperation(IBindCtx
        *)override{
            operation_ = true;
            return S_OK;
        }
        HRESULT STDMETHODCALLTYPE
        InOperation(BOOL
        * value)override{
            if (!value)return E_POINTER;
            *value = operation_;
            return S_OK;
        }
        HRESULT STDMETHODCALLTYPE
        EndOperation(HRESULT, IBindCtx
        *,DWORD)override{
            operation_ = false;
            return S_OK;
        }
    };

    class CopyDropSource final : public IDropSource {
        std::atomic <ULONG> refs_{1};
    public:
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
        QueryContinueDrag(BOOL
        escape,
        DWORD keys
        )override{ return escape ? DRAGDROP_S_CANCEL : (!(keys & MK_LBUTTON) ? DRAGDROP_S_DROP : S_OK); }
        HRESULT STDMETHODCALLTYPE
        GiveFeedback(DWORD)
        override{ return DRAGDROP_S_USEDEFAULTCURSORS; }
    };
}
