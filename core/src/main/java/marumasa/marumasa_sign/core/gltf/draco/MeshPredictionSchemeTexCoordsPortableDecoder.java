package marumasa.marumasa_sign.core.gltf.draco;
class MeshPredictionSchemeTexCoordsPortableDecoder extends MeshPredictionScheme
{    
    MeshPredictionSchemeTexCoordsPortablePredictor predictor_;
    public MeshPredictionSchemeTexCoordsPortableDecoder(PointAttribute attribute, PredictionSchemeTransform transform, MeshPredictionSchemeData meshData)
    {
        super(attribute, transform, meshData);
        this.predictor_ = new MeshPredictionSchemeTexCoordsPortablePredictor(meshData);
    }
    
    @Override
    public void setParentAttribute(PointAttribute att)
        throws DrakoException
    {
        if (att == null || (att.getAttributeType() != AttributeType.POSITION))
            throw DracoUtils.failed();
        // Invalid attribute type.
        if (att.getComponentsCount() != 3)
            throw DracoUtils.failed();
        // Currently works only for 3 component positions.
        predictor_.pos_attribute_ = att;
    }
    
    @Override
    public void computeOriginalValues(IntSpan inCorr, IntSpan outData, int size, int numComponents, int[] entryToPointIdMap)
        throws DrakoException
    {
        predictor_.entry_to_point_id_map_ = entryToPointIdMap;
        this.transform_.initializeDecoding(numComponents);
        int corner_map_size = this.meshData.dataToCornerMap.getCount();
        for (int p = 0; p < corner_map_size; ++p)
        {
            int corner_id = this.meshData.dataToCornerMap.get(p);
            if (!predictor_.computePredictedValue(false, corner_id, outData, p))
                throw DracoUtils.failed();
            int dst_offset = p * numComponents;
            this.transform_.computeOriginalValue(IntSpan.wrap(predictor_.predicted_value_), 0, inCorr, dst_offset, outData, dst_offset);
        }
        
    }
    
    @Override
    public void decodePredictionData(DecoderBuffer buffer)
        throws DrakoException
    {
        int num_orientations = buffer.decodeI32();
        if (num_orientations < 0)
            throw DracoUtils.failed();
        predictor_.resizeOrientations(num_orientations);
        boolean last_orientation = true;
        RAnsBitDecoder decoder = new RAnsBitDecoder();
        decoder.startDecoding(buffer);
        for (int i = 0; i < num_orientations; ++i)
        {
            if (!decoder.decodeNextBit())
            {
                last_orientation = !last_orientation;
            }
            
            predictor_.set_orientation(i, last_orientation);
        }
        
        decoder.endDecoding();
        super.decodePredictionData(buffer);
    }
    
    @Override
    public int getParentAttributeType(int i)
    {
        return AttributeType.POSITION;
    }
    
    public int getNumParentAttributes()
    {
        return 1;
    }
    
        @Override
    public void computeCorrectionValues(IntSpan in_data, IntSpan out_corr, int size, int num_components, int[] entry_to_point_id_map) {
        throw new UnsupportedOperationException();
    }
    public int getPredictionMethod() {
        return PredictionSchemeMethod.TEX_COORDS_PORTABLE;
    }
    
}
