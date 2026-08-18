import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Dal logo grande all'icona adattiva di Android.
 *
 * Un'icona adattiva e' 108x108 unita' e il launcher ne ritaglia il bordo in forme diverse:
 * cerchio, squircle, goccia. Solo il riquadro centrale da 72 e' garantito visibile, quindi
 * il disegno va li' dentro e tutto il resto e' margine sacrificabile.
 *
 * ## Due scelte che non sono ovvie
 *
 * **Si tiene solo l'emblema.** La scritta "MFOOT MULTIPLAYER SOCCER" a quarantotto pixel
 * non si legge: diventa una striscia grigia che sporca il segno invece di dirlo. Il ritaglio
 * si ferma sopra le lettere, e va misurato con attenzione perche' un ritaglio troppo largo
 * ne fa spuntare i tetti — che e' peggio del testo intero, sembra un errore.
 *
 * **Lo sfondo del logo diventa lo sfondo dell'icona.** Il ritaglio e' un rettangolo con
 * dentro il blu dello stadio, e su un livello di sfondo di un altro colore si vedrebbe il
 * bordo del rettangolo. Campionando il colore da un angolo del logo, il rettangolo sparisce
 * dentro il fondo e l'emblema sembra ritagliato davvero.
 */
public final class Icona {

    /**
     * Il riquadro dell'emblema nel logo, in frazioni.
     *
     * Misurati sull'immagine: lo scudo comincia a un quarto della larghezza, il pallone
     * finisce a quattro quinti, e la scritta comincia poco sotto il 60% dell'altezza.
     */
    private static final double SX = 0.25, DX = 0.81, SU = 0.16, GIU = 0.59;

    /** Quanto dell'icona occupa l'emblema. Il resto e' il margine che il launcher taglia. */
    private static final double DENTRO = 0.70;

    public static void main(String[] args) throws Exception {
        BufferedImage logo = ImageIO.read(new File(args[0]));
        File res = new File(args[1]);

        int w = logo.getWidth();
        int h = logo.getHeight();

        int x = (int) Math.round(SX * w);
        int y = (int) Math.round(SU * h);
        int larghezza = (int) Math.round((DX - SX) * w);
        int altezza = (int) Math.round((GIU - SU) * h);
        BufferedImage emblema = logo.getSubimage(x, y, larghezza, altezza);

        // Il colore di fondo, preso dove il logo e' sicuramente sfondo: in alto a sinistra.
        int fondo = logo.getRGB((int) (0.04 * w), (int) (0.5 * h)) & 0xFFFFFF;

        String[] nomi = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] misure = {108, 162, 216, 324, 432};
        for (int i = 0; i < nomi.length; i++) {
            scrivi(emblema, misure[i], fondo, new File(res, "mipmap-" + nomi[i]));
        }

        System.out.printf("emblema %dx%d, fondo #%06X%n", larghezza, altezza, fondo);
    }

    /**
     * Un livello di primo piano a una densita'.
     *
     * L'emblema conserva le sue proporzioni e viene centrato: allungarlo per riempire un
     * quadrato deformerebbe il giocatore, e si nota.
     */
    private static void scrivi(BufferedImage emblema, int lato, int fondo, File cartella)
            throws Exception {
        if (!cartella.exists() && !cartella.mkdirs()) {
            throw new IllegalStateException("non riesco a creare " + cartella);
        }

        BufferedImage out = new BufferedImage(lato, lato, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Lo sfondo si dipinge anche qui, non solo nel livello sotto: cosi' il rettangolo
        // dell'emblema non ha bordi visibili contro il fondo, a qualunque zoom.
        g.setColor(new Color(fondo));
        g.fillRect(0, 0, lato, lato);

        double disponibile = lato * DENTRO;
        double scala = Math.min(disponibile / emblema.getWidth(), disponibile / emblema.getHeight());
        int lw = (int) Math.round(emblema.getWidth() * scala);
        int lh = (int) Math.round(emblema.getHeight() * scala);

        // I bordi del ritaglio si sfumano prima di posarlo.
        //
        // Il fondo dietro l'emblema nel logo non e' piatto — ha il bagliore dei riflettori —
        // quindi contro un colore pieno il rettangolo si vede lo stesso, come una toppa.
        // Sfumando l'ultimo decimo a trasparente il passaggio sparisce.
        BufferedImage sfumato = sfuma(emblema);
        g.drawImage(sfumato, (lato - lw) / 2, (lato - lh) / 2, lw, lh, null);
        g.dispose();

        ImageIO.write(out, "png", new File(cartella, "ic_launcher_foreground.png"));
    }

    /**
     * Rende trasparente l'ultimo decimo di bordo, con una salita morbida.
     *
     * La sfumatura e' il prodotto delle due distanze — orizzontale e verticale — cosi' gli
     * angoli sfumano piu' in fretta dei lati, che e' quello che serve: un angolo netto si
     * nota molto piu' di un lato.
     */
    private static BufferedImage sfuma(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        double bordoX = w * 0.10;
        double bordoY = h * 0.10;

        for (int y = 0; y < h; y++) {
            double fy = Math.min(Math.min(y, h - 1 - y) / bordoY, 1.0);
            for (int x = 0; x < w; x++) {
                double fx = Math.min(Math.min(x, w - 1 - x) / bordoX, 1.0);
                // Curva morbida invece che lineare: una rampa dritta lascia una riga
                // visibile dove comincia, e l'occhio la trova.
                double f = morbida(fx) * morbida(fy);

                int rgb = src.getRGB(x, y);
                int alfa = (int) Math.round(255 * f);
                out.setRGB(x, y, (alfa << 24) | (rgb & 0xFFFFFF));
            }
        }
        return out;
    }

    /** Smoothstep: parte piatta, sale, arriva piatta. Nessuno spigolo dove comincia. */
    private static double morbida(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3 - 2 * c);
    }
}
