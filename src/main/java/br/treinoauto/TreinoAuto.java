package br.treinoauto;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Treino da Sala do Tempo (Mr. Popo) - servidor Rede Warriors.
 * F8 liga/desliga. Com o bot ligado, ao abrir um minigame (Gravidade, Controle, Ritmo, Precisao)
 * ele joga sozinho, usando o MESMO caminho de entrada do jogo (as mesmas teclas/cliques que voce daria),
 * so que na hora exata, lendo a simulacao do proprio minigame.
 */
public final class TreinoAuto {
    static final String TELA = "com.nonopichy.yggdrasilremake.client.warriors.saladotempo.TelaMinigameSala";
    static final String RITMO = "com.nonopichy.yggdrasilremake.common.warriors.saladotempo.MinigameSala$Ritmo";

    // indices de tecla do minigame: 0 cima, 1 esquerda, 2 baixo, 3 direita. Soltar = 4 + indice. Clique = 10.
    static final KeyMapping KEY = new KeyMapping("Treino automatico: ligar/desligar", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "Treino AutoFarm");

    static boolean ligado = false;
    static boolean teclaAntes = false;
    static int ultimoTick = -1;
    static Object telaAtual;

    private TreinoAuto() {}

    @Mod.EventBusSubscriber(modid = "treinoauto", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void teclas(RegisterKeyMappingsEvent e) { e.register(KEY); }
    }

    @Mod.EventBusSubscriber(modid = "treinoauto", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        // a cada quadro: le o F8 direto do teclado (funciona com o minigame aberto) e joga
        @SubscribeEvent
        public static void quadro(TickEvent.RenderTickEvent e) {
            if (e.phase != TickEvent.Phase.START) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            boolean d = InputConstants.isKeyDown(mc.getWindow().getWindow(), KEY.getKey().getValue());
            if (d && !teclaAntes) {
                ligado = !ligado;
                mc.player.displayClientMessage(Component.literal("[Treino] Bot " + (ligado ? "LIGADO" : "DESLIGADO")), false);
            }
            teclaAntes = d;
            if (!ligado) return;
            Screen s = mc.screen;
            if (s == null || !s.getClass().getName().equals(TELA)) { ultimoTick = -1; telaAtual = null; return; }
            if (s != telaAtual) { telaAtual = s; ultimoTick = -1; }
            try { jogarQuadro(s); }
            catch (Throwable ex) {
                ex.printStackTrace();
                ligado = false;
                mc.player.displayClientMessage(Component.literal("[Treino] Erro, desliguei: " + ex), false);
            }
        }

        @SubscribeEvent
        public static void desenho(ScreenEvent.Render.Post e) {
            if (!e.getScreen().getClass().getName().equals(TELA)) return;
            Minecraft mc = Minecraft.getInstance();
            e.getGuiGraphics().drawString(mc.font, ligado ? "BOT LIGADO (F8)" : "bot desligado (F8)", 6, 6, ligado ? 0x55FF55 : 0xAAAAAA);
        }
    }

    // ------------------------------------------------------------------
    static void jogarQuadro(Object tela) throws Exception {
        if (!(Boolean) campo(tela, "jogando") || campo(tela, "fim") != null
                || (Boolean) campo(tela, "parou") || (Boolean) campo(tela, "esperandoFim")) return;

        invocar(tela, "avancar"); // deixa a simulacao em dia antes de decidir
        Object sim = campo(tela, "sim");
        int tick = (Integer) pub(sim, "tick");
        if (tick == ultimoTick) return; // uma decisao por tick (50 ms)
        ultimoTick = tick;

        int tipo = (Integer) pub(sim, "tipo");
        int niveis = (Integer) pub(sim, "niveis");
        switch (tipo) {
            case 1 -> gravidade(tela, sim);
            case 2 -> controle(tela, sim, niveis);
            case 3 -> ritmo(tela, sim);
            case 4 -> precisao(tela, sim, niveis);
            default -> {}
        }
    }

    // ---- 1) GRAVIDADE: empurra para o lado pedido sempre que o indicador desce ----
    static void gravidade(Object tela, Object sim) throws Exception {
        Object g = pub(sim, "gravidade");
        float y = (Float) pub(g, "indicadorY");
        int lado = (Integer) pub(g, "ladoPedido"); // -1 esquerda, +1 direita
        if (y > 30f) enviar(tela, lado < 0 ? 1 : 3, 0, 0);
    }

    // ---- 2) CONTROLE: mantem o cursor dentro da zona que anda ----
    static void controle(Object tela, Object sim, int niveis) throws Exception {
        Object c = pub(sim, "controle");
        float cur = (Float) pub(c, "cursorX");
        float zona = (Float) pub(c, "zonaMeio");
        int dir = (Integer) pub(c, "zonaDir");
        int larg = (Integer) pub(c, "zonaLargura");
        double vz = 1.2 + 0.15 * niveis;
        float alvo = (float) (zona + dir * vz);
        alvo = Math.max(larg / 2f, Math.min(200f - larg / 2f, alvo));
        float err = alvo - cur;
        boolean querD = err > 1.375f, querE = err < -1.375f; // o cursor anda 2,75 por tick
        boolean estaE = (Boolean) pub(c, "esquerda"), estaD = (Boolean) pub(c, "direita");
        if (querD != estaD) enviar(tela, querD ? 3 : 7, 0, 0);
        if (querE != estaE) enviar(tela, querE ? 1 : 5, 0, 0);
    }

    // ---- 3) RITMO: aperta a seta certa quando a nota esta dentro da janela PERFEITA (14) ----
    static void ritmo(Object tela, Object sim) throws Exception {
        Object r = pub(sim, "ritmo");
        List<?> notas = (List<?>) pub(r, "notas");
        boolean[] ap = (boolean[]) pub(r, "apertadas");
        float v = (Float) r.getClass().getMethod("velocidade").invoke(r);

        boolean[] segurar = new boolean[4];
        boolean[] apertar = new boolean[4];
        for (Object n : new ArrayList<>(notas)) {
            int seta = (Integer) pub(n, "seta");
            if ((Boolean) pub(n, "ativa")) { segurar[seta] = true; continue; } // nota longa em andamento: segura
            boolean esq = (Boolean) pub(n, "esquerda");
            float x = (Float) pub(n, "x");
            float alvoX = esq ? 150f : 230f;
            float dist = Math.abs(x - alvoX);
            float prox = Math.abs((esq ? x + v : x - v) - alvoX);
            // perfeito: dist <= 14. Se a nota anda tao rapido que pode pular a janela, aperta no ultimo tick bom.
            if (dist <= 13.9f || (dist < 29.5f && prox > 29.5f)) apertar[seta] = true;
        }
        for (int seta = 0; seta < 4; seta++) {
            int k = teclaDaSeta(seta);
            if (k < 0) continue;
            if (ap[seta] && !segurar[seta]) enviar(tela, 4 + k, 0, 0); // solta
            if (apertar[seta] && !ap[seta]) enviar(tela, k, 0, 0);     // aperta
        }
    }

    static int teclaDaSeta(int seta) throws Exception {
        Method m = Class.forName(RITMO, true, Minecraft.getInstance().getClass().getClassLoader()).getMethod("setaDaTecla", int.class);
        for (int k = 0; k < 4; k++) if ((Integer) m.invoke(null, k) == seta) return k;
        return -1;
    }

    // ---- 4) PRECISAO: clica no centro do alvo quando o anel chega no raio (14 +- 4 = perfeito) ----
    static void precisao(Object tela, Object sim, int niveis) throws Exception {
        Object p = pub(sim, "precisao");
        List<?> alvos = (List<?>) pub(p, "alvos");
        double vel = 0.7 + 0.18 * niveis;
        for (Object a : new ArrayList<>(alvos)) {
            if ((Boolean) pub(a, "sumindo")) continue;
            float anel = (Float) pub(a, "anel");
            boolean agora = (anel - 14f <= 3.2f) || (anel - vel < 10.5f);
            if (anel <= 18.5f && agora) enviar(tela, 10, (Integer) pub(a, "x"), (Integer) pub(a, "y"));
        }
    }

    // ------------------------------------------------------------------
    static void enviar(Object tela, int a, int b, int c) throws Exception {
        Method m = tela.getClass().getDeclaredMethod("jogar", int.class, int.class, int.class);
        m.setAccessible(true);
        m.invoke(tela, a, b, c);
    }

    static void invocar(Object o, String nome) throws Exception {
        Method m = o.getClass().getDeclaredMethod(nome);
        m.setAccessible(true);
        m.invoke(o);
    }

    static Object campo(Object o, String nome) throws Exception {
        Field f = o.getClass().getDeclaredField(nome);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object pub(Object o, String nome) throws Exception {
        return o.getClass().getField(nome).get(o);
    }
}
