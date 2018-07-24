package clients.symphony.api;

import clients.ISymClient;
import clients.SymBotClient;
import exceptions.*;
import model.ClientError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

public abstract class APIClient {
    private final Logger logger = LoggerFactory.getLogger(APIClient.class);

    protected void handleError(Response response, ISymClient botClient) throws SymClientException {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SERVER_ERROR) {
            logger.error(response.getStatusInfo().getReasonPhrase());
            throw new ServerErrorException(response.getStatusInfo().getReasonPhrase());
        } else {
            ClientError error = response.readEntity((ClientError.class));
            if (response.getStatus() == 400) {
                logger.error("Client error occurred", error);
                throw new APIClientErrorException(error.getMessage());
            } else if (response.getStatus() == 401) {
                logger.error("User unauthorized, refreshing tokens");
                if(botClient!=null)
                    botClient.getSymAuth().authenticate();
                throw new UnauthorizedException(error.getMessage());
            } else if (response.getStatus() == 403) {
                logger.error("Forbidden: Caller lacks necessary entitlement.");
                throw new ForbiddenException(error.getMessage());
            }
        }


    }
}
