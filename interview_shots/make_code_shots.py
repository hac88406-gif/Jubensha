# -*- coding: utf-8 -*-
"""用 pygments + Pillow 生成代码高亮截图（深色主题，带行号，逐 token 着色）"""
import os
from PIL import Image, ImageDraw, ImageFont
from pygments import lex
from pygments.lexers import JavaLexer, LuaLexer
from pygments.token import Token

OUT = os.path.dirname(os.path.abspath(__file__))
FONT_PATH = r"C:\Windows\Fonts\msyh.ttc"
FONT_SIZE = 19
LINE_H = 31
TOP_MARGIN = 30
RIGHT_PAD = 40
BG = (30, 30, 30)

COLORS = {
    Token:                 (0xD8, 0xD8, 0xD8),
    Token.Comment:         (0x80, 0x90, 0x84),
    Token.Comment.Single:  (0x80, 0x90, 0x84),
    Token.Keyword:         (0xF9, 0x26, 0x72),
    Token.Keyword.Constant:(0xAE, 0x81, 0xFF),
    Token.Keyword.Declaration: (0xF9, 0x26, 0x72),
    Token.String:          (0xE6, 0xDB, 0x74),
    Token.String.Doc:      (0x80, 0x90, 0x84),
    Token.Number:          (0xAE, 0x81, 0xFF),
    Token.Name:            (0xD8, 0xD8, 0xD8),
    Token.Name.Function:   (0xA6, 0xE2, 0x2E),
    Token.Name.Class:      (0xA6, 0xE2, 0x2E),
    Token.Name.Builtin:    (0xA6, 0xE2, 0x2E),
    Token.Name.Builtin.Pseudo: (0xA6, 0xE2, 0x2E),
    Token.Name.Attribute:  (0xA6, 0xE2, 0x2E),
    Token.Name.Tag:        (0xF9, 0x26, 0x72),
    Token.Name.Decorator:  (0xA6, 0xE2, 0x2E),
    Token.Operator:        (0xF9, 0x26, 0x72),
    Token.Operator.Word:   (0xF9, 0x26, 0x72),
    Token.Punctuation:     (0xD8, 0xD8, 0xD8),
    Token.Literal.String.Double: (0xE6, 0xDB, 0x74),
    Token.Literal.String.Single: (0xE6, 0xDB, 0x74),
}

def tok_color(ttype):
    while ttype is not None:
        if ttype in COLORS:
            return COLORS[ttype]
        ttype = ttype.parent
    return COLORS[Token]

def render(title, code, lexer, out_name):
    source_lines = code.split('\n')
    font = ImageFont.truetype(FONT_PATH, FONT_SIZE)

    # 按行累积 lexer token
    per_line = [[] for _ in source_lines]
    cur_line = 0
    for ttype, value in lex(code, lexer):
        parts = value.split('\n')
        for idx, part in enumerate(parts):
            if part:
                if cur_line < len(per_line):
                    per_line[cur_line].append((ttype, part))
            if idx < len(parts) - 1:
                cur_line += 1

    max_w = 0
    for toks in per_line:
        w = sum(font.getlength(t) for _, t in toks)
        max_w = max(max_w, w)

    line_no_w = font.getlength(str(len(source_lines))) + 22
    margin = int(line_no_w + 34)
    img_w = max(int(max_w) + margin + RIGHT_PAD, 1200)
    img_h = TOP_MARGIN + LINE_H * len(source_lines) + 26

    img = Image.new('RGB', (img_w, img_h), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, img_w, TOP_MARGIN], fill=(22, 22, 22))
    d.text((16, 6), title, font=ImageFont.truetype(FONT_PATH, 16), fill=(0xE0, 0xE0, 0xE0))
    d.rectangle([0, TOP_MARGIN, margin - 8, img_h], fill=(24, 24, 24))
    d.line([margin - 8, TOP_MARGIN, margin - 8, img_h], fill=(45, 45, 45), width=1)

    y = TOP_MARGIN + 3
    for i in range(len(source_lines)):
        d.text((8, y), str(i + 1), font=font, fill=(0x68, 0x68, 0x68))
        x = margin
        for ttype, t in per_line[i]:
            d.text((x, y), t, font=font, fill=tok_color(ttype))
            x += font.getlength(t)
        y += LINE_H
    img.save(out_name, 'PNG')
    return out_name, img.size

def render_pages(title, code, lexer, out_prefix, lines_per_page=95):
    source_lines = code.split('\n')
    pages = []
    for start in range(0, len(source_lines), lines_per_page):
        chunk = '\n'.join(source_lines[start:start+lines_per_page])
        out_name = f"{out_prefix}_{start+1}-{min(start+lines_per_page, len(source_lines))}.png"
        pages.append(render(f"{title}  [{start+1}-{min(start+lines_per_page, len(source_lines))}/{len(source_lines)}]", chunk, lexer, os.path.join(OUT, out_name)))
    return pages

if __name__ == '__main__':
    base = r"D:\javaProgram\urban-script-reservation"
    java = open(os.path.join(base, "order-service\\src\\main\\java\\com\\urban\\script\\order\\service\\StockService.java"), encoding='utf-8').read()
    lua = open(os.path.join(base, "order-service\\src\\main\\resources\\lua\\reserve_stock.lua"), encoding='utf-8').read()
    r1 = render_pages("StockService.java - Redis+Lua 原子扣减（SHA1 预加载 / 懒加载 / MySQL 降级）", java, JavaLexer(), os.path.join(OUT, "code_stock_service"))
    r2 = render("reserve_stock.lua - 原子扣减脚本（HASH 结构）", lua, LuaLexer(), os.path.join(OUT, "code_reserve_lua.png"))
    for p in r1:
        print(p)
    print(r2)
