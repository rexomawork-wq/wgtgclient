def find_class(class_name):
    from java import jclass
    try:
        return jclass(class_name).class_
    except Exception:
        return None

def _field(clazz, name):
    while clazz is not None:
        try:
            field = clazz.getDeclaredField(name)
            field.setAccessible(True)
            return field
        except Exception:
            clazz = clazz.getSuperclass()
    return None

def get_private_field(obj, field_name):
    try:
        return _field(obj.getClass(), field_name).get(obj)
    except Exception:
        return None

def set_private_field(obj, field_name, new_value):
    try:
        _field(obj.getClass(), field_name).set(obj, new_value)
        return True
    except Exception:
        return False

def get_static_private_field(clazz, field_name):
    try:
        return _field(clazz, field_name).get(None)
    except Exception:
        return None

def set_static_private_field(clazz, field_name, new_value):
    try:
        _field(clazz, field_name).set(None, new_value)
        return True
    except Exception:
        return False
