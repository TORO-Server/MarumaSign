package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

public class DrakoException extends IOException {
    public DrakoException() {
        super();
    }
    public DrakoException(String message) {
        super(message);
    }
    public DrakoException(String message, Throwable cause) {
        super(message, cause);
    }
    public DrakoException(Throwable cause) {
        super(cause);
    }
}