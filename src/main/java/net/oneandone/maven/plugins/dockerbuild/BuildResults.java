package net.oneandone.maven.plugins.dockerbuild;

import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.BuildResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

public class BuildResults extends ResultCallbackTemplate<BuildImageResultCallback, BuildResponseItem> {
    private final PrintWriter logfile;

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildImageResultCallback.class);

    private String imageId;

    private String error;

    @Override
    public void onNext(BuildResponseItem item) {
        String stream;

        stream = item.getStream();
        if (stream != null) {
            logfile.print(stream);
        }
        if (item.isBuildSuccessIndicated()) {
            this.imageId = item.getImageId();
        } else if (item.isErrorIndicated()) {
            this.error = item.getError();
        }
        LOGGER.debug(item.toString());
    }

    public String awaitImageId() {
        try {
            awaitCompletion();
        } catch (InterruptedException e) {
            throw new DockerClientException("", e);
        }

        return getImageId();
    }

    private String getImageId() {
        if (imageId != null) {
            return imageId;
        }

        if (error == null) {
            throw new DockerClientException("Could not build image");
        }

        throw new DockerClientException("Could not build image: " + error);
    }

    public BuildResults(PrintWriter logfile) {
        this.logfile = logfile;
    }
}
