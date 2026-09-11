def get_environment():
    raise NotImplementedError("Elyx environment APIs are not implemented in wgtg")

def import_module(name, package=None):
    import importlib
    return importlib.import_module(name, package)

class _Unavailable:
    def __getattr__(self, name):
        raise NotImplementedError("This Elyx API is not supported by wgtg yet")

assets = strings = settings = metainfo = refmap = _Unavailable()
