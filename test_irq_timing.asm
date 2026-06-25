#define SCREEN $0400
#define D011   $d011
#define D012   $d012
#define D019   $d019
#define D01A   $d01a
#define D020   $d020
#define CIA1TA $dc04

* = $0801
.byte $0b,$08,$0a,$00,$9e,$32,$30,$36,$34,$00,$00,$00

* = $0810

start
    sei

    ; Set KERNAL IRQ vector
    lda #<irq_handler
    sta $0314
    lda #>irq_handler
    sta $0315

    ; Set raster IRQ line to $40 (64)
    lda #$40
    sta D012
    lda D011
    and #$7f
    sta D011

    ; Enable raster IRQ
    lda #$01
    sta D01A

    ; Start CIA1 Timer A as free-running counter (for cycle measurement)
    lda #$ff
    sta $dc04
    sta $dc05
    lda #$11
    sta $dc0e

    ; Clear screen
    ldx #$00
    lda #$20
clr
    sta SCREEN,x
    sta SCREEN+$100,x
    sta SCREEN+$200,x
    sta SCREEN+$2e8,x
    inx
    bne clr

    ; Labels on screen (PETSCII screen codes)
    lda #18        ; R
    sta SCREEN+0
    lda #1         ; A
    sta SCREEN+1
    lda #19        ; S
    sta SCREEN+2
    lda #20        ; T
    sta SCREEN+3
    lda #58        ; :
    sta SCREEN+4

    lda #20        ; T
    sta SCREEN+40
    lda #1         ; A
    sta SCREEN+41
    lda #58        ; :
    sta SCREEN+42

    lda #4         ; D
    sta SCREEN+80
    lda #49        ; 0 (screen code for 0)
    sta SCREEN+81
    lda #49        ; 1
    sta SCREEN+82
    lda #49        ; 1
    sta SCREEN+83

    cli

loop
    ; Count frames
    inc SCREEN+120
    lda SCREEN+120
    cmp #$20+100
    bne loop
    lda #$20
    sta SCREEN+120
    jmp loop

; ----------------------------------------
irq_handler
    ; Read timer immediately to measure when we got here
    lda CIA1TA     ; timer counts DOWN from $ffff
    sta SCREEN+5   ; show raw timer low byte at raster line

    ; Read current raster
    lda D012
    sta SCREEN+6   ; show raster position

    ; Flash border to mark visually
    inc D020

    ; Acknowledge raster IRQ
    lda #$01
    sta D019

    ; Now do the D011 write (what demos do)
    lda CIA1TA
    sta SCREEN+43  ; timer before D011 write

    lda #$1b
    sta D011

    lda CIA1TA
    sta SCREEN+44  ; timer after D011 write

    lda D012
    sta SCREEN+84  ; raster after D011 write

    ; Restore border
    dec D020

    jmp $ea31
