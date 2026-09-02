#!/usr/bin/env python3
"""
Liczy sumę kontrolną migracji tak, jak robi to Flyway 9.x.

Do czego to jest
────────────────
Gdy Flyway odmawia startu z komunikatem:

    Migration checksum mismatch for migration version 65
    -> Applied to database : -1682047706
    -> Resolved locally    : -1477878544

ten skrypt pozwala ustalić, KTÓRA wersja pliku odpowiada której liczbie — bez zgadywania
i bez czekania na czyjąś pamięć. Odpalony na kilku rewizjach z historii gita wskazuje
dokładnie, co jest zapisane w bazie, a co siedzi w zbudowanym jarze.

Naprawa jest wtedy jednym UPDATE-em (dokładnie to robi `flyway repair`):

    UPDATE flyway_schema_history SET checksum = <resolved locally> WHERE version = '65';

Algorytm
────────
org.flywaydb.core.internal.resource.ChecksumCalculator: CRC32 po bajtach UTF-8 każdej
linii, BEZ znaków końca linii. Stąd bierze się rzecz, która zaskakuje ludzi najczęściej —
zmiana samego komentarza też zmienia sumę, bo liczona jest z całego pliku.

Użycie
──────
    python3 deploy/sql/flyway_checksum.py src/main/resources/db/migration/V97__*.sql

    # która rewizja odpowiada sumie z bazy:
    for r in a960d94 9814e30 HEAD; do
        git show $r:src/main/resources/db/migration/V97__dashboard_hint_dismissals.sql \
          | python3 deploy/sql/flyway_checksum.py - | sed "s/^/$r /"
    done

Przypomnienie: to narzędzie diagnostyczne, nie zaproszenie do edytowania migracji.
Migracja wykonana na jakimkolwiek środowisku jest niezmienna — poprawka idzie do nowego
pliku. Ten skrypt istnieje, bo tę regułę raz złamano i trzeba było posprzątać.
"""
import sys
import zlib


def flyway_checksum(data: bytes) -> int:
    crc = 0
    for line in data.decode("utf-8").splitlines():
        crc = zlib.crc32(line.encode("utf-8"), crc)
    # Flyway rzutuje wynik CRC32 (long) na int — czyli signed 32-bit
    return crc - 2**32 if crc >= 2**31 else crc


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip())
        return 2

    for path in argv[1:]:
        data = sys.stdin.buffer.read() if path == "-" else open(path, "rb").read()
        name = "<stdin>" if path == "-" else path
        print(f"{flyway_checksum(data):>13}  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
