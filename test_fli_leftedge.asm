; test_fli_leftedge.asm — deterministic FLI left-edge test (K3-class).
; Polling-based (no IRQ → no thrash, identical under detSysJump & autostart).
; MC bitmap FLI, 38-col (CSEL=0), xscroll=7. VIC bank 1 ($4000-$7fff),
; bitmap $6000, 8 screen matrices $4000..$5c00, color RAM $D800.
;
; Build: 64tass --case-sensitive -a test_fli_leftedge.asm -o test_fli_leftedge.prg

D011 = $d011
D012 = $d012
D016 = $d016
D018 = $d018
D019 = $d019
D01A = $d01a
DD00 = $dd00
DD02 = $dd02

        * = $0801
        .byte $0c,$08,$0a,$00,$9e,$32,$30,$36,$34,$00,$00,$00  ; SYS 2064

        * = $0810
start
        sei
        lda #$7f
        sta $dc0d           ; disable CIA IRQs
        sta $dd0d
        lda $dc0d
        lda $dd0d
        lda #$00
        sta D01A            ; no VIC IRQs (polling)

        lda #$35            ; KERNAL/BASIC off, I/O on
        sta $01

        ; VIC bank 1 ($4000-$7fff)
        lda DD02
        ora #$03
        sta DD02
        lda DD00
        and #$fc
        ora #$02
        sta DD00

        ; bitmap $6000..$7f3f = $ff (all foreground pixels)
        lda #<$6000
        sta $fb
        lda #>$6000
        sta $fc
        ldx #$20
        ldy #0
        lda #$ff
fillbm  sta ($fb),y
        iny
        bne fillbm
        inc $fc
        dex
        bne fillbm

        ; 8 screen matrices $4000..$5fff = $1a
        lda #<$4000
        sta $fb
        lda #>$4000
        sta $fc
        ldx #$20
        ldy #0
        lda #$1a
fillsc  sta ($fb),y
        iny
        bne fillsc
        inc $fc
        dex
        bne fillsc

        ; color RAM $D800 = $0c
        ldx #0
        lda #$0c
fillcol sta $d800,x
        sta $d900,x
        sta $da00,x
        sta $db00,x
        inx
        bne fillcol

        lda #$06            ; D021 background = blue
        sta $d021
        lda #$00
        sta $d020           ; border black
        lda #$01
        sta $d022
        lda #$02
        sta $d023

        lda #$17            ; D016: MCM=1, CSEL=0 (38col), xscroll=7
        sta D016
        lda #$3b            ; D011: BMM,DEN,RSEL, ysmooth=3
        sta D011

; ---- polling FLI: each frame, wait raster $2f, run the FLI kernel ----
mainloop
        ; wait until raster == $2f (top of display)
w1      lda D012
        cmp #$2f
        bne w1
        ; small fixed settle so each frame starts near the same cycle
        ; (a few cyc of jitter from the compare loop is tolerable — the loop
        ; raster-locks via the badline steal once it gets going)

        ldx #0
fli
        lda d018tab,x       ; 4
        sta D018            ; 4
        lda d011tab,x       ; 4
        sta D011            ; 4  force badline (ysmooth ladder)
        ; padding: tune so body+badline-steal = 63 cyc/line (raster-lock)
        nop                 ; 2
        nop                 ; 2
        nop                 ; 2
        inx                 ; 2
        cpx #190            ; 2
        bne fli             ; 3
        jmp mainloop

; D018 table: screen nibble 0..7, bitmap bit set
d018tab
        .for i := 0, i < 200, i += 1
        .byte ((i & 7) << 4) | $08
        .next
; D011 table: ysmooth = (raster $2f+1+i) & 7 ; $30 & 7 = 0, so = i & 7
d011tab
        .for i := 0, i < 200, i += 1
        .byte $38 | (i & 7)
        .next
