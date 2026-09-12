from dataclasses import dataclass, field

@dataclass
class Header: text: str
@dataclass
class Text:
    text: str
    subtext: str = None
    icon: str = None
    accent: bool = False
    red: bool = False
    on_click: object = None
    on_long_click: object = None
    create_sub_fragment: object = None
    link_alias: str = None
@dataclass
class Switch:
    key: str
    text: str
    default: bool = False
    subtext: str = None
    icon: str = None
    on_change: object = None
    on_long_click: object = None
    link_alias: str = None
@dataclass
class Input:
    key: str
    text: str
    default: str = ""
    subtext: str = None
    icon: str = None
    on_change: object = None
    on_long_click: object = None
    link_alias: str = None

@dataclass
class Selector:
    key: str
    text: str
    default: int = 0
    items: list = field(default_factory=list)
    icon: str = None
    on_change: object = None
    on_long_click: object = None
    link_alias: str = None

@dataclass
class Divider:
    text: str = None

@dataclass
class EditText:
    key: str
    hint: str
    default: str = ""
    multiline: bool = False
    max_length: int = 0
    mask: str = None
    on_change: object = None
