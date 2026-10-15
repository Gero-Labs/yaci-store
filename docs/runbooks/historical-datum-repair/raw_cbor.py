"""Bounded byte-span CBOR inspection. Datum hashes always cover untouched source bytes."""
import hashlib

class Invalid(ValueError):
    pass

class Reader:
    def __init__(self, raw):
        if not raw or len(raw) > 1_048_576:
            raise Invalid('CBOR size outside budget')
        self.raw, self.pos, self.nodes = raw, 0, 0

    def number(self, additional):
        if additional < 24:
            return additional
        widths = {24: 1, 25: 2, 26: 4, 27: 8}
        width = widths.get(additional)
        if width is None or self.pos + width > len(self.raw):
            raise Invalid('Invalid CBOR argument')
        value = int.from_bytes(self.raw[self.pos:self.pos + width], 'big')
        self.pos += width
        return value

    def item(self, depth=0):
        self.nodes += 1
        if depth > 64 or self.nodes > 100_000 or self.pos >= len(self.raw):
            raise Invalid('CBOR structure outside budget')
        start = self.pos
        initial = self.raw[self.pos]
        self.pos += 1
        major, additional = initial >> 5, initial & 31
        indefinite = additional == 31
        if indefinite and major not in (2, 3, 4, 5):
            raise Invalid('Invalid indefinite item')
        value = None if indefinite else self.number(additional)
        children = []
        if major in (0, 1):
            scalar = value if major == 0 else -1 - value
        elif major in (2, 3):
            if indefinite:
                chunks = []
                while self.pos < len(self.raw) and self.raw[self.pos] != 255:
                    child = self.item(depth + 1)
                    if child['major'] != major or child['indefinite']:
                        raise Invalid('Invalid byte/text chunk')
                    children.append(child)
                    chunks.append(child['value'])
                self.break_()
                scalar = b''.join(chunks)
            else:
                if self.pos + value > len(self.raw):
                    raise Invalid('Truncated bytes')
                scalar = self.raw[self.pos:self.pos + value]
                self.pos += value
        elif major in (4, 5):
            if indefinite:
                while self.pos < len(self.raw) and self.raw[self.pos] != 255:
                    children.append(self.item(depth + 1))
                self.break_()
                if major == 5 and len(children) % 2:
                    raise Invalid('Odd indefinite map')
            else:
                for _ in range(value * (2 if major == 5 else 1)):
                    children.append(self.item(depth + 1))
            scalar = None
        elif major == 6:
            children = [self.item(depth + 1)]
            scalar = value
        elif major == 7:
            scalar = value
        else:
            raise Invalid('Unknown CBOR major')
        return {'major': major, 'value': scalar, 'children': children,
                'start': start, 'end': self.pos, 'indefinite': indefinite}

    def break_(self):
        if self.pos >= len(self.raw) or self.raw[self.pos] != 255:
            raise Invalid('Missing CBOR break')
        self.pos += 1


def one(raw):
    reader = Reader(raw)
    item = reader.item()
    if reader.pos != len(raw):
        raise Invalid('Trailing CBOR item')
    return item


def plutus(item):
    major, value, children = item['major'], item['value'], item['children']
    if major in (0, 1, 2):
        return
    if major in (4, 5):
        for child in children:
            plutus(child)
        return
    if major == 6:
        child = children[0]
        if value in (2, 3) and child['major'] == 2:
            return
        if (121 <= value <= 127 or 1280 <= value <= 1400) and child['major'] == 4:
            for field in child['children']:
                plutus(field)
            return
        if value == 102 and child['major'] == 4 and len(child['children']) == 2:
            alternative, fields = child['children']
            if alternative['major'] == 0 and fields['major'] == 4:
                for field in fields['children']:
                    plutus(field)
                return
    raise Invalid('Not Plutus Data')


def datum(raw_hex):
    try:
        raw = bytes.fromhex(raw_hex)
    except (TypeError, ValueError) as error:
        raise Invalid('Invalid datum hex') from error
    if len(raw_hex) != len(raw) * 2:
        raise Invalid('Non-hex whitespace')
    plutus(one(raw))
    return hashlib.blake2b(raw, digest_size=32).hexdigest()


def map_get(item, key):
    if item['major'] != 5:
        raise Invalid('Expected CBOR map')
    matches = [v for k, v in zip(item['children'][::2], item['children'][1::2])
               if k['major'] == 0 and k['value'] == key]
    if len(matches) > 1:
        raise Invalid('Duplicate transaction map key')
    return matches[0] if matches else None


def witness_datums(raw_hex, expected_tx):
    try:
        raw = bytes.fromhex(raw_hex)
    except (TypeError, ValueError) as error:
        raise Invalid('Invalid transaction hex') from error
    if len(raw_hex) != len(raw) * 2:
        raise Invalid('Non-hex transaction whitespace')
    root = one(raw)
    if root['major'] != 4 or len(root['children']) not in (3, 4):
        raise Invalid('Invalid transaction envelope')
    body, witness = root['children'][:2]
    if body['major'] != 5 or witness['major'] != 5:
        raise Invalid('Invalid transaction body/witnesses')
    if len(root['children']) == 4:
        validity, auxiliary = root['children'][2:]
        if validity['major'] != 7 or validity['value'] not in (20, 21):
            raise Invalid('Invalid transaction validity')
    else:
        auxiliary = root['children'][2]
    if not (auxiliary['major'] == 5 or auxiliary['major'] == 7 and auxiliary['value'] == 22
            or auxiliary['major'] == 6 and auxiliary['value'] == 259 and auxiliary['children'][0]['major'] == 5):
        raise Invalid('Invalid transaction auxiliary data')
    actual = hashlib.blake2b(raw[body['start']:body['end']], digest_size=32).hexdigest()
    if actual != expected_tx:
        raise Invalid('Transaction identity mismatch')
    values = map_get(witness, 4)
    if values is None:
        return []
    if values['major'] == 6 and values['value'] == 258:
        values = values['children'][0]
    if values['major'] != 4:
        raise Invalid('Invalid witness datum set')
    result = []
    for index, item in enumerate(values['children']):
        plutus(item)
        original = raw[item['start']:item['end']]
        result.append({'index': index, 'cbor': original.hex(),
                       'raw_hash': hashlib.blake2b(original, digest_size=32).hexdigest()})
    return result
