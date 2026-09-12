from copy import deepcopy


class Strings:
    def __init__(self, all_strings):
        self._strings = deepcopy(all_strings)

    def get_with_locale(self, key, locale="en", default=None):
        fallback = key if default is None else default
        return deepcopy(self._strings.get(locale, {}).get(key, self._strings.get("en", {}).get(key, fallback)))

    def get(self, key, default=None):
        from . import get_environment
        try:
            locale = get_environment().get("locale")
        except RuntimeError:
            locale = None
        if not locale:
            try:
                from java import jclass
                locale = str(jclass("java.util.Locale").getDefault().getLanguage())
            except ImportError:
                locale = "en"
        return self.get_with_locale(key, locale, default)

    def __call__(self, key, *args, locale=None, default=None, **kwargs):
        value = self.get_with_locale(key, locale, default) if locale else self.get(key, default)
        return value.format(*args, **kwargs) if isinstance(value, str) else value

    __getitem__ = get

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return self.get(name)

    def pluralize(self, number, key):
        forms = self.get(key)
        if not isinstance(forms, (list, tuple)) or len(forms) != 3:
            raise ValueError("Plural forms must contain three values")
        n = abs(number)
        index = 0 if n % 10 == 1 and n % 100 != 11 else 1 if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14 else 2
        return f"{number} {forms[index]}"
