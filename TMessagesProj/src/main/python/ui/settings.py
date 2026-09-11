from dataclasses import dataclass

@dataclass
class Header: text: str
@dataclass
class Text: text: str; subtext: str = None; icon: str = None
@dataclass
class Switch: key: str; text: str; default: bool = False; subtext: str = None; icon: str = None
@dataclass
class Input: key: str; text: str; default: str = ""; subtext: str = None; icon: str = None
