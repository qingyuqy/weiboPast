import sys
from pypdf import PdfWriter, PdfReader

def main():
    out = sys.argv[1]
    pdfs = sys.argv[2:]
    w = PdfWriter()
    total = 0
    for p in pdfs:
        r = PdfReader(p)
        total += len(r.pages)
        for page in r.pages:
            w.add_page(page)
    with open(out, 'wb') as f:
        w.write(f)
    print('merged pages:', total)

if __name__ == '__main__':
    main()