package app.nxextract.core;

/** Erro de formato ou de chave, com mensagem pronta para mostrar ao usuário. */
public class NxException extends Exception {
    public NxException(String message) {
        super(message);
    }
}
