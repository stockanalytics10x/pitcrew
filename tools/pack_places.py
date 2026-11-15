"""Pack a place TSV (id, name, lat, lon, address, kind, business, hours) smaller:
rows sorted by address, coordinates as 1e-5° deltas from the previous row, optional ids dropped."""
import sys, gzip
src, dst, keep_id = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
lines = gzip.open(src, "rt", encoding="utf-8").read().split("\n")
head = [l for l in lines if l.startswith("#")]
rows = [l.split("\t") for l in lines if l and not l.startswith("#")]
rows.sort(key=lambda r: (r[4], r[1]))
out = head + ["#!d5"]
pl = pn = 0
for r in rows:
    la = round(float(r[2]) * 1e5); lo = round(float(r[3]) * 1e5)
    out.append("\t".join([r[0] if keep_id else "", r[1], str(la - pl), str(lo - pn)] + r[4:8]))
    pl, pn = la, lo
with gzip.GzipFile(dst, "wb", compresslevel=9, mtime=0) as f:
    f.write(("\n".join(out) + "\n").encode("utf-8"))
print(dst, len(rows))
