import { ConfigService } from '@nestjs/config';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { createApplication } from './init-app';

async function bootstrap(): Promise<void> {
  const app = await createApplication();

  const config = new DocumentBuilder()
    .setTitle('Sprint 08 Auth Service')
    .setDescription('Standalone authentication service per the Sprint 08 contract')
    .setVersion('1.0.0')
    .addBearerAuth()
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('docs', app, document, { jsonDocumentUrl: 'docs/json' });

  const configService = app.get(ConfigService);
  const port = configService.get<number>('port')!;
  await app.listen(port);
  // eslint-disable-next-line no-console
  console.log(`Sprint 08 auth service listening on http://localhost:${port}`);
  // eslint-disable-next-line no-console
  console.log(`Swagger UI at http://localhost:${port}/docs`);
}
bootstrap();