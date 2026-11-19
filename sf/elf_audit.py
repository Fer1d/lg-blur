import struct

def require(ok, message):
    if not ok:
        raise ValueError(message)

class ELF:
    def __init__(self, data):
        self.data = data
        require(data[:7] == b'\x7fELF\x02\x01\x01', 'Not little-endian ELF64')
        h = struct.unpack_from('<HHIQQQIHHHHHH', data, 16)
        require(h[0:3] == (3, 183, 1), 'Not AArch64 ET_DYN')
        require(h[8] == 56 and h[10] == 64, 'Unexpected ELF entry sizes')
        self.ph = [struct.unpack_from('<IIQQQQQQ', data, h[4]+i*56) for i in range(h[9])]
        sh = [struct.unpack_from('<IIQQQQIIQQ', data, h[5]+i*64) for i in range(h[11])]
        strings = data[sh[h[12]][4]:sh[h[12]][4]+sh[h[12]][5]]
        self.sections = {strings[s[0]:].split(b'\0', 1)[0].decode(): s for s in sh}

    def offset(self, va, size=4, executable=False):
        matches = [p for p in self.ph if p[0] == 1 and p[3] <= va and va+size <= p[3]+p[5]]
        require(len(matches) == 1, 'VA lacks unique file-backed LOAD')
        p = matches[0]
        require(not executable or p[1] & 1, 'Patch VA not executable')
        return p[2] + va-p[3]

    def section(self, name):
        s = self.sections[name]
        return self.data[s[4]:s[4]+s[5]]

    def word(self, va):
        return struct.unpack_from('<I', self.data, self.offset(va))[0]

    def relocations(self):
        # Android APS2 packed RELA, Android RELR, and ordinary PLT RELA.
        b = self.section('.rela.dyn')
        require(b[:4] == b'APS2', 'Expected Android packed RELA')
        pos = 4
        def sleb():
            nonlocal pos
            v = shift = 0
            while True:
                require(pos < len(b) and shift < 70, 'Malformed SLEB128')
                x = b[pos]; pos += 1
                v |= (x & 127) << shift; shift += 7
                if not x & 128:
                    return v - (1 << shift) if x & 64 else v
        count, offset = sleb(), sleb()
        require(0 <= count < 1000000, 'Bad relocation count')
        entries = []; addend = 0
        while len(entries) < count:
            n, flags = sleb(), sleb()
            require(0 < n <= count-len(entries) and not flags & ~15, 'Bad relocation group')
            delta = sleb() if flags & 2 else None
            info = sleb() if flags & 1 else None
            require(not flags & 4 or flags & 8, 'Invalid addend group')
            ad = sleb() if flags & 8 and flags & 4 else None
            for _ in range(n):
                offset += delta if flags & 2 else sleb()
                ri = info if flags & 1 else sleb()
                addend = addend + (ad if flags & 4 else sleb()) if flags & 8 else 0
                entries.append((offset, ri & 0xffffffff, addend, 'APS2'))
        require(pos == len(b), 'Trailing APS2 bytes')
        b = self.section('.relr.dyn'); base = 0
        require(len(b) % 8 == 0, 'Bad RELR length')
        for (entry,) in struct.iter_unpack('<Q', b):
            if entry & 1:
                for bit in range(1, 64):
                    if entry & (1 << bit):
                        entries.append((base+(bit-1)*8, 1027, None, 'RELR'))
                base += 63*8
            else:
                entries.append((entry, 1027, None, 'RELR')); base = entry+8
        for offset, info, addend in struct.iter_unpack('<QQq', self.section('.rela.plt')):
            entries.append((offset, info & 0xffffffff, addend, 'RELA_PLT'))
        return entries
