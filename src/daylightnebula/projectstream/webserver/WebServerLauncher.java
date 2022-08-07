package daylightnebula.projectstream.webserver;

public class WebServerLauncher {
    public static void main(String[] args) {
        WebServer.INSTANCE.start(8080);
        //ImageProcessor.INSTANCE.runTest();
    }
}
