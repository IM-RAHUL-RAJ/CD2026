import {
  INestApplication,
  UnprocessableEntityException,
  ValidationPipe,
} from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { ErrorEnvelopeFilter } from './common/error.envelope.filter';
// eslint-disable-next-line @typescript-eslint/no-require-imports
import cookieParser = require('cookie-parser');

/**
 * Builds the application with production bootstrap wiring (CORS with
 * credentials, the cookie parser, the global ValidationPipe and the error
 * envelope filter). Calling the controller through this factory means an
 * integration test exercises the same pipeline as `npm start`.
 */
export async function createApplication(): Promise<INestApplication> {
  const app = await NestFactory.create(AppModule, {
    cors: { origin: true, credentials: true },
  });

  app.use(cookieParser());

  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      transform: true,
      stopAtFirstError: false,
      exceptionFactory: (errors) => {
        const messages = errors.flatMap((error) =>
          Object.values(error.constraints ?? {}),
        );
        return new UnprocessableEntityException({
          errorCode: 'VAL-422',
          message: messages.join('; ') || 'Invalid input',
        });
      },
    }),
  );
  app.useGlobalFilters(new ErrorEnvelopeFilter());

  return app;
}