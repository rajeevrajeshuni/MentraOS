// Public endpoint: get permissions without auth
// GET /api/public/permissions/:packageName
import { Router, Request } from 'express';
import App from '../../../models/app.model';


const router = Router();

router.get('/:packageName', async (req: Request, res) => {
  try {
    const { packageName } = req.params;

    // find the app
    const app = await App.findOne({ packageName });

    if (!app) {
      return res.status(404).json({ error: "App not found" });
    }

    // return its permissions
    return res.json({
      success: true,
      packageName,
      permissions: app.permissions || []
    });

  } catch (error) {
    return res.status(500).json({ error: "Internal server error" });
  }
});

export default router;



