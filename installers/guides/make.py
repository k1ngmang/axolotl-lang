"""Builds the installation and command line guides, one PDF per system and language.

    python3 installers/guides/make.py [version]

Writes docs/guides/axolotl-<version>-<system>-<language>.pdf. Needs reportlab and the Liberation and DejaVu fonts.
"""

import re
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (KeepTogether, ListFlowable, ListItem, Paragraph, Preformatted,
                                SimpleDocTemplate, Spacer, Table, TableStyle)

from content import guide

FONTS = Path("/usr/share/fonts/truetype")
ACCENT = colors.HexColor("#c2410c")
CODE_BACKGROUND = colors.HexColor("#f4f1ee")
NOTE_BACKGROUND = colors.HexColor("#fdf3e7")
MUTED = colors.HexColor("#6b6461")


def register_fonts():
    for face, file in (("Sans", "Regular"), ("Sans-Bold", "Bold"), ("Sans-Italic", "Italic"),
                       ("Sans-BoldItalic", "BoldItalic")):
        pdfmetrics.registerFont(TTFont(face, FONTS / "liberation" / f"LiberationSans-{file}.ttf"))
    pdfmetrics.registerFont(TTFont("Mono", FONTS / "dejavu" / "DejaVuSansMono.ttf"))
    pdfmetrics.registerFontFamily("Sans", normal="Sans", bold="Sans-Bold", italic="Sans-Italic",
                                  boldItalic="Sans-BoldItalic")


STYLES = {
    "title": ParagraphStyle("title", fontName="Sans-Bold", fontSize=22, leading=27, spaceAfter=4),
    "subtitle": ParagraphStyle("subtitle", fontName="Sans", fontSize=11, leading=15, textColor=MUTED,
                               spaceAfter=14),
    "h1": ParagraphStyle("h1", fontName="Sans-Bold", fontSize=14, leading=18, textColor=ACCENT,
                         spaceBefore=14, spaceAfter=6),
    "h2": ParagraphStyle("h2", fontName="Sans-Bold", fontSize=11, leading=14, spaceBefore=8, spaceAfter=4),
    "body": ParagraphStyle("body", fontName="Sans", fontSize=9.5, leading=13.5, alignment=TA_LEFT,
                           spaceAfter=5),
    "cell": ParagraphStyle("cell", fontName="Sans", fontSize=8.5, leading=11.5),
    "code": ParagraphStyle("code", fontName="Mono", fontSize=8.3, leading=11),
}


def inline(text):
    """`code`, **bold** and *italic* in a paragraph, with the rest escaped."""
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = re.sub(r"`([^`]+)`", r'<font name="Mono" color="#7c2d12">\1</font>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", text)
    return re.sub(r"(?<![\w*])\*([^*]+)\*(?![\w*])", r"<i>\1</i>", text)


def wrapped(line, width=92):
    """A long output line, such as an error message, broken at spaces and indented."""
    lines = []
    while len(line) > width and " " in line[:width]:
        cut = line.rfind(" ", 0, width)
        lines.append(line[:cut])
        line = "    " + line[cut + 1:]
    return lines + [line]


def code_block(text):
    lines = [part for line in text.strip("\n").split("\n") for part in wrapped(line)]
    block = Preformatted("\n".join(lines), STYLES["code"])
    table = Table([[block]], colWidths=[170 * mm])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), CODE_BACKGROUND),
        ("LEFTPADDING", (0, 0), (-1, -1), 8), ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 6), ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    return [table, Spacer(1, 6)]


def note(text):
    table = Table([[Paragraph(inline(text), STYLES["body"])]], colWidths=[170 * mm])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), NOTE_BACKGROUND),
        ("LINEBEFORE", (0, 0), (0, -1), 2.5, ACCENT),
        ("LEFTPADDING", (0, 0), (-1, -1), 9), ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    return [table, Spacer(1, 7)]


def table(rows, widths):
    cells = [[Paragraph(inline(cell), STYLES["cell"]) for cell in row] for row in rows]
    result = Table(cells, colWidths=[w * mm for w in widths], repeatRows=1)
    result.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), CODE_BACKGROUND),
        ("FONTNAME", (0, 0), (-1, 0), "Sans-Bold"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.4, colors.HexColor("#ddd6d1")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    return [result, Spacer(1, 8)]


def bullets(items, numbered=False):
    entries = [ListItem(Paragraph(inline(item), STYLES["body"]), leftIndent=14) for item in items]
    kind = {"bulletType": "1", "bulletFontName": "Sans-Bold"} if numbered else {
        "bulletType": "bullet", "start": "•", "bulletFontName": "Sans"}
    return [ListFlowable(entries, leftIndent=14, bulletFontSize=9, **kind), Spacer(1, 4)]


def flowables(blocks):
    story = []
    for kind, *args in blocks:
        if kind == "title":
            story.append(Paragraph(inline(args[0]), STYLES["title"]))
            story.append(Paragraph(inline(args[1]), STYLES["subtitle"]))
        elif kind in ("h1", "h2"):
            # A heading stays on the page of what follows it.
            story.append(("heading", Paragraph(inline(args[0]), STYLES[kind])))
        elif kind == "p":
            story.append(Paragraph(inline(args[0]), STYLES["body"]))
        elif kind == "code":
            story.extend(code_block(args[0]))
        elif kind == "note":
            story.extend(note(args[0]))
        elif kind == "table":
            story.extend(table(args[0], args[1]))
        elif kind in ("list", "steps"):
            story.extend(bullets(args[0], numbered=kind == "steps"))
    return keep_headings(story)


def keep_headings(story):
    result = []
    i = 0
    while i < len(story):
        item = story[i]
        if isinstance(item, tuple):
            group = [item[1]]
            if i + 1 < len(story) and not isinstance(story[i + 1], tuple):
                group.append(story[i + 1])
                i += 1
            result.append(KeepTogether(group))
        else:
            result.append(item)
        i += 1
    return result


def footer(text):
    def draw(canvas, doc):
        canvas.saveState()
        canvas.setFont("Sans", 7.5)
        canvas.setFillColor(MUTED)
        canvas.drawString(20 * mm, 12 * mm, text)
        canvas.drawRightString(190 * mm, 12 * mm, str(doc.page))
        canvas.restoreState()
    return draw


def build(version, system, language, out_dir):
    blocks, meta = guide(version, system, language)
    path = out_dir / f"axolotl-{version}-{system}-{language}.pdf"
    doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm,
                            topMargin=18 * mm, bottomMargin=20 * mm, title=meta["title"],
                            author="Axolotl", subject=meta["subject"], lang=language)
    doc.build(flowables(blocks), onFirstPage=footer(meta["footer"]), onLaterPages=footer(meta["footer"]))
    return path


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "0.1.1"
    register_fonts()
    out_dir = Path(__file__).resolve().parents[2] / "docs" / "guides"
    out_dir.mkdir(parents=True, exist_ok=True)
    for system in ("windows", "macos", "linux"):
        for language in ("en", "ru"):
            print(build(version, system, language, out_dir))


if __name__ == "__main__":
    main()
