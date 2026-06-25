; test_fli_leftedge.asm — minimal deterministic FLI repro of the Krestage-3
; picture-mover left-edge gray: multicolor bitmap FLI, 38-col (CSEL=0),
; xscroll=7, with a left-edge content pattern. No VSP-bug trigger → VICE
; renders it deterministically, unlike Krestage 3.
;
; Build: 64tass --case-sensitive -a test_fli_leftedge.asm -o test_fli_leftedge.prg
;
; VIC bank 1 ($4000-$7fff). Bitmap at $6000. 8 screen matrices $4000..$5c00
; (one per FLI line, $D018 high nibble 0..7). Color RAM $D800.

D011 = $d011
D016 = $d016
D018 = $d018
D012 = $d012
D019 = $d019
D01A = $d01a
DD00 = $dd00
DD02 = $dd02

        * = $0801
        .byte $0c,$08,$0a,$00,$9e,$32,$30,$36,$34,$00,$00,$00  ; SYS 2064

        * = $0810
start
        sei
        lda #$35            ; KERNAL+BASIC off, I/O on (RAM under for fill)
        sta $01

        ; VIC bank 1 ($4000-$7fff): DD00 bits %10 (inverted) = bank 1
        lda DD02
        ora #$03
        sta DD02
        lda DD00
        and #$fc
        ora #$02
        sta DD00

        ; ---- fill bitmap $6000..$7f3f ----
        ; left two chars of every char-row = solid (content); rest = blank.
        ; bitmap byte layout: 8 bytes/cell, cells left-to-right then down.
        ; We just fill ALL bitmap = $ff (every pixel foreground) so the left
        ; edge is unambiguously "content" — the bug is whether the leftmost
        ; char is shown (JaC) or border/background (VICE) in 38-col+xscroll7.
        lda #<$6000
        sta $fb
        lda #>$6000
        sta $fc
        ldx #$20            ; $2000 bytes (~8000) → 32 pages
        ldy #0
        lda #$ff
fillbm  sta ($fb),y
        iny
        bne fillbm
        inc $fc
        dex
        bne fillbm

        ; ---- fill 8 screen matrices $4000..$5fff with $1a ----
        ; screen nibble hi=$1 (color1), lo=$a → MC bitmap fg colors.
        lda #<$4000
        sta $fb
        lda #>$4000
        sta $fc
        ldx #$20            ; $2000 bytes = 8 × $400
        ldy #0
        lda #$1a
fillsc  sta ($fb),y
        iny
        bne fillsc
        inc $fc
        dex
        bne fillsc

        ; ---- fill color RAM $D800 with $0c (gray) ----
        ldx #0
        lda #$0c
fillcol sta $d800,x
        sta $d900,x
        sta $da00,x
        sta $db00,x
        inx
        bne fillcol

        ; background / border
        lda #$06            ; D021 = blue (like K3 deer)
        sta $d021
        lda #$00            ; D020 = black border
        sta $d020
        lda #$01
        sta $d022           ; MC color regs
        lda #$02
        sta $d023

        ; multicolor bitmap, 38-col (CSEL=0), xscroll=7
        lda #$17            ; MCM=1, CSEL=0, xscroll=7
        sta D016
        ; D011 = BMM=1, DEN=1, RSEL=1, ysmooth=3 ($3b)
        lda #$3b
        sta D011
        lda #$18            ; placeholder; FLI loop drives D018
        sta D018

        ; raster IRQ at line $30 to start the FLI kernel
        lda #<irq
        sta $fffe
        lda #>irq
        sta $ffff
        lda #$30
        sta D012
        lda D011
        and #$7f
        sta D011
        lda #$01
        sta D01A            ; enable raster IRQ
        lda D019
        sta D019            ; ack
        cli
hang    jmp hang

; ----------------------------------------------------------------------
; FLI kernel: at raster $30, run a 63-cycle/line loop for ~200 lines,
; writing D018 (cycle screen matrix) + D011 (ysmooth = line&7 → badline
; every line). The mid-line writes land after the badline check → the
; FLI-bug prefetch on the leftmost cells, exactly like K3.
; ----------------------------------------------------------------------
irq
        pha
        txa
        pha
        tya
        pha
        lda D019
        sta D019            ; ack raster IRQ

        ; Tight FLI loop: 21 instruction-cycles/iter; each iter writes D018
        ; + D011(ysmooth) which forces a badline → ~40 stolen cycles → ~61-63
        ; wall-clock/line, self-paced by the badline steal. Mid-line writes
        ; land after the badline check → FLI-bug prefetch on leftmost cells.
        ldx #0              ; line index
fli
        lda d018tab,x       ; 4  D018 for this line
        sta D018            ; 4
        lda d011tab,x       ; 4  D011 = $38 | (line & 7)
        sta D011            ; 4  forces badline
        inx                 ; 2
        cpx #190            ; 2
        bne fli             ; 3  (=21 cyc + ~40 steal ≈ 61/line)
        ; end FLI

        pla
        tay
        pla
        tax
        pla
        rti

; D018 table: screen nibble cycles 0..7 (matrices $4000..$5c00), bitmap bit set
d018tab
        .for i := 0, i < 200, i += 1
        .byte ((i & 7) << 4) | $08
        .next
d011tab
        .for i := 0, i < 200, i += 1
        .byte $38 | (i & 7)
        .next
